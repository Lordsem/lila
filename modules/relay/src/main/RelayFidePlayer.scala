package lila.relay

import reactivemongo.api.bson.Macros.Annotations.Key
import reactivemongo.api.bson.BSONDocumentWriter
import akka.util.ByteString
import akka.stream.scaladsl.*
import akka.stream.contrib.ZipInputStreamSource
import play.api.libs.ws.StandaloneWSClient
import java.io.InputStream
import java.util.zip.ZipInputStream
import chess.format.pgn.{ Tag, Tags }
import chess.ByColor

import lila.db.dsl.{ *, given }

private enum FideTC:
  case Standard, Rapid, Blitz

case class RelayFidePlayer(
    @Key("_id") id: FideId,
    name: String,
    fed: Option[String],
    title: Option[UserTitle],
    standard: Option[Int],
    rapid: Option[Int],
    blitz: Option[Int]
):
  def ratingOf(tc: FideTC) = tc match
    case FideTC.Standard => standard
    case FideTC.Rapid    => rapid
    case FideTC.Blitz    => blitz

final private class RelayFidePlayerApi(colls: RelayColls, cacheApi: lila.memo.CacheApi)(using Executor):
  private val coll = colls.fidePlayer
  import BSONHandlers.given

  def upsert(ps: Seq[RelayFidePlayer]) = coll: c =>
    val update = c.update(ordered = false)
    for
      elements <- ps.traverse: p =>
        update.element(
          q = $id(p.id),
          u = summon[BSONDocumentWriter[RelayFidePlayer]].writeOpt(p).get,
          upsert = true
        )
      _ <- elements.nonEmpty so update.many(elements).void
    yield ()

  def enrichGames(tour: RelayTour)(games: RelayGames): Fu[RelayGames] =
    val tc = guessTimeControl(tour) | FideTC.Standard
    games.traverse: game =>
      for
        white <- FideId.from(game.tags(_.WhiteFideId).flatMap(_.toIntOption)).so(cache.get)
        black <- FideId.from(game.tags(_.BlackFideId).flatMap(_.toIntOption)).so(cache.get)
      yield game.copy(tags = update(game.tags, tc, ByColor(white, black)))

  private val cache = cacheApi[FideId, Option[RelayFidePlayer]](1024, "relay.fidePlayer"):
    _.expireAfterWrite(1.minute).buildAsyncFuture: id =>
      coll(_.byId[RelayFidePlayer](id))

  private def guessTimeControl(tour: RelayTour): Option[FideTC] =
    tour.description.split('|').lift(2).map(_.trim.toLowerCase.replace("classical", "standard")) so: tcStr =>
      FideTC.values.find(tc => tcStr.contains(tc.toString.toLowerCase))

  private def update(tags: Tags, tc: FideTC, fidePlayers: ByColor[Option[RelayFidePlayer]]): Tags =
    chess.Color.all.foldLeft(tags): (tags, color) =>
      tags ++ Tags:
        fidePlayers(color).so: fide =>
          List(
            Tag(color.fold(Tag.White, Tag.Black), fide.name).some,
            fide.title.map { title => Tag(color.fold(Tag.WhiteTitle, Tag.BlackTitle), title.value) },
            fide.ratingOf(tc) map: rating =>
              Tag(color.fold(Tag.WhiteElo, Tag.BlackElo), rating.toString)
          ).flatten

final private class RelayFidePlayerUpdate(api: RelayFidePlayerApi, ws: StandaloneWSClient)(using
    scheduler: Scheduler
)(using Executor, akka.stream.Materializer):

  scheduler.scheduleWithFixedDelay(1.hour, 1.hour): () =>
    if nowDateTime.getDayOfWeek == java.time.DayOfWeek.SUNDAY && nowDateTime.getHour == 4 then apply()

  // the file is big. We want to stream the http response into the zip reader,
  // and stream the zip output into the database as it's being extracted.
  // Don't load the whole thing in memory.
  def apply(): Funit =
    ws.url("http://ratings.fide.com/download/players_list.zip").stream() flatMap:
      case res if res.status == 200 =>
        ZipInputStreamSource: () =>
          ZipInputStream(res.bodyAsSource.runWith(StreamConverters.asInputStream()))
        .map(_._2)
          .via(Framing.delimiter(akka.util.ByteString("\r\n"), maximumFrameLength = 200))
          .map(_.utf8String)
          .drop(1) // first line is a header
          .map(parseLine)
          .mapConcat(_.toList)
          .grouped(100)
          .mapAsync(1)(api.upsert)
          .runWith(lila.common.LilaStream.sinkCount)
          .monSuccess(_.relay.fidePlayers.update)
          .addEffect(lila.mon.relay.fidePlayers.nb.update(_))
          .addEffect(nb => logger.info(s"RelayFidePlayerApi.update done $nb"))
          .void
      case res => fufail(s"RelayFidePlayerApi.pull ${res.status} ${res.statusText}")

  /*
6502938        Acevedo Mendez, Lisseth                                      ISL F   WIM  WIM                     1795  0   20 1767  14  20 1740  0   20 1993  w
6504450        Acevedo Mendez, Oscar                                        CRC M                                1779  0   40              1640  0   20 1994  i
   */
  private def parseLine(line: String): Option[RelayFidePlayer] =
    def string(start: Int, end: Int) = line.substring(start, end).trim.some.filter(_.nonEmpty)
    def number(start: Int, end: Int) = string(start, end).flatMap(_.toIntOption)
    for
      id   <- number(0, 15)
      name <- string(15, 76)
      title  = string(84, 89).flatMap(lila.user.Title.get)
      wTitle = string(89, 105).flatMap(lila.user.Title.get)
    yield RelayFidePlayer(
      id = FideId(id),
      name = name,
      fed = string(76, 79),
      title = lila.user.Title.mostValuable(title, wTitle),
      standard = number(113, 117),
      rapid = number(126, 132),
      blitz = number(139, 145)
    )