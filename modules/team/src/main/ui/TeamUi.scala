package lila.team
package ui

import scalalib.paginator.Paginator

import lila.ui.*
import ScalatagsTemplate.{ *, given }
import lila.common.MarkdownRender
import lila.core.team.LightTeam

final class TeamUi(helpers: Helpers)(using Executor):
  import helpers.{ *, given }
  import trans.{ team as trt }

  object markdown:
    private val renderer = MarkdownRender(header = true, list = true, table = true)
    private val cache = lila.memo.CacheApi.scaffeineNoScheduler
      .expireAfterAccess(10 minutes)
      .maximumSize(1024)
      .build[Markdown, Html]()
    def apply(team: Team, text: Markdown): Frag = rawHtml(cache.get(text, renderer(s"team:${team.id}")))

  def menu(currentTab: Option[String])(using ctx: PageContext) =
    val tab = ~currentTab
    st.nav(cls := "page-menu__menu subnav")(
      (ctx.teamNbRequests > 0).option(
        a(cls := tab.active("requests"), href := routes.Team.requests)(
          trt.xJoinRequests.pluralSame(ctx.teamNbRequests)
        )
      ),
      ctx.isAuth.option(
        a(cls := tab.active("mine"), href := routes.Team.mine)(trt.myTeams())
      ),
      ctx.isAuth.option(
        a(cls := tab.active("leader"), href := routes.Team.leader)(trt.leaderTeams())
      ),
      a(cls := tab.active("all"), href := routes.Team.all())(trt.allTeams()),
      ctx.isAuth.option(
        a(cls := tab.active("form"), href := routes.Team.form)(trt.newTeam())
      )
    )

  def teamTr(t: Team.WithMyLeadership)(using Context) =
    val isMine = isMyTeamSync(t.id)
    tr(cls := "paginated")(
      td(cls := "subject")(
        a(
          dataIcon := Icon.Group,
          cls := List(
            "team-name text" -> true,
            "mine"           -> isMine
          ),
          href := routes.Team.show(t.id)
        )(
          t.name,
          t.flair.map(teamFlair),
          t.amLeader.option(em("leader"))
        ),
        ~t.intro: String
      ),
      td(cls := "info")(
        p(trans.team.nbMembers.plural(t.nbMembers, t.nbMembers.localize)),
        isMine.option:
          st.form(action := routes.Team.quit(t.id), method := "post")(
            submitButton(cls := "button button-empty button-red button-thin confirm team__quit")(
              trans.team.quitTeam.txt()
            )
          )
      )
    )

  def membersPage(t: Team, pager: Paginator[TeamMember.UserAndDate])(using Context) =
    main(cls := "page-small box")(
      boxTop(
        h1(
          teamLink(t.light, true),
          " • ",
          trt.nbMembers.plural(t.nbMembers, t.nbMembers.localize)
        )
      ),
      table(cls := "team-members slist slist-pad"):
        tbody(cls := "infinite-scroll")(
          pager.currentPageResults.map { case TeamMember.UserAndDate(u, date) =>
            tr(cls := "paginated")(
              td(lightUserLink(u)),
              td(momentFromNowOnce(date))
            )
          },
          pagerNextTable(pager, np => routes.Team.members(t.slug, np).url)
        )
    )

  def members(team: Team, members: Paginator[lila.core.LightUser])(using Translate) =
    div(cls := "team-show__members")(
      st.section(cls := "recent-members")(
        h2(a(href := routes.Team.members(team.slug))(trt.teamRecentMembers())),
        div(cls := "userlist infinite-scroll")(
          members.currentPageResults.map: member =>
            div(cls := "paginated")(lightUserLink(member)),
          pagerNext(members, np => routes.Team.show(team.id, np).url)
        )
      )
    )

  def actions(
      team: Team,
      member: Option[TeamMember],
      myRequest: Option[TeamRequest],
      subscribed: Boolean,
      asMod: Boolean
  )(using ctx: Context) =
    def hasPerm(perm: TeamSecurity.Permission.Selector) = member.exists(_.hasPerm(perm))
    val canManage                                       = asMod && Granter.opt(_.ManageTeam)
    div(cls := "team-show__actions")(
      (team.enabled && member.isEmpty).option(
        frag(
          if myRequest.exists(_.declined) then
            frag(
              strong(trt.requestDeclined()),
              a(cls := "button disabled button-metal")(trt.joinTeam())
            )
          else if myRequest.isDefined then
            frag(
              strong(trt.beingReviewed()),
              postForm(action := routes.Team.quit(team.id)):
                submitButton(cls := "button button-red button-empty confirm")(trans.site.cancel())
            )
          else (ctx.isAuth && !asMod).option(joinButton(team))
        )
      ),
      (team.enabled && member.isDefined).option(
        postForm(
          cls    := "team-show__subscribe form3",
          action := routes.Team.subscribe(team.id)
        )(
          div(
            span(form3.cmnToggle("team-subscribe", "subscribe", checked = subscribed)),
            label(`for` := "team-subscribe")(trt.subToTeamMessages.txt())
          )
        )
      ),
      (member.isDefined && !hasPerm(_.Admin)).option(
        postForm(cls := "quit", action := routes.Team.quit(team.id))(
          submitButton(cls := "button button-empty button-red confirm")(trt.quitTeam.txt())
        )
      ),
      (team.enabled && hasPerm(_.Tour)).option(
        frag(
          a(
            href     := routes.Tournament.teamBattleForm(team.id),
            cls      := "button button-empty text",
            dataIcon := Icon.Trophy
          ):
            span(
              strong(trt.teamBattle()),
              em(trt.teamBattleOverview())
            )
          ,
          a(
            href     := s"${routes.Tournament.form}?team=${team.id}",
            cls      := "button button-empty text",
            dataIcon := Icon.Trophy
          ):
            span(
              strong(trt.teamTournament()),
              em(trt.teamTournamentOverview())
            )
          ,
          a(
            href     := s"${routes.Swiss.form(team.id)}",
            cls      := "button button-empty text",
            dataIcon := Icon.Trophy
          ):
            span(
              strong(trans.swiss.swissTournaments()),
              em(trt.swissTournamentOverview())
            )
        )
      ),
      (team.enabled && hasPerm(_.PmAll)).option(
        frag(
          a(
            href     := routes.Team.pmAll(team.id),
            cls      := "button button-empty text",
            dataIcon := Icon.Envelope
          ):
            span(
              strong(trt.messageAllMembers()),
              em(trt.messageAllMembersOverview())
            )
        )
      ),
      ((team.enabled && hasPerm(_.Settings)) || canManage).option(
        a(
          href     := routes.Team.edit(team.id),
          cls      := "button button-empty text",
          dataIcon := Icon.Gear
        )(
          trans.settings.settings()
        )
      ),
      ((team.enabled && hasPerm(_.Admin)) || canManage).option(
        a(
          cls      := "button button-empty text",
          href     := routes.Team.leaders(team.id),
          dataIcon := Icon.Group
        )(trt.teamLeaders())
      ),
      ((team.enabled && hasPerm(_.Kick)) || canManage).option(
        a(
          cls      := "button button-empty text",
          href     := routes.Team.kick(team.id),
          dataIcon := Icon.InternalArrow
        )(trt.kickSomeone())
      ),
      ((team.enabled && hasPerm(_.Request)) || canManage).option(
        a(
          cls      := "button button-empty text",
          href     := routes.Team.declinedRequests(team.id),
          dataIcon := Icon.Cancel
        )(trt.declinedRequests())
      ),
      ((Granter.opt(_.ManageTeam) || Granter.opt(_.Shusher)) && !asMod).option(
        a(
          href := routes.Team.show(team.id, 1, mod = true),
          cls  := "button button-red"
        ):
          "View team as Mod"
      )
    )

  // handle special teams here
  private def joinButton(t: Team)(using Context) =
    t.id.value match
      case "english-chess-players" => joinAt("https://ecf.octoknight.com/")
      case "ecf"                   => joinAt(routes.Team.show("english-chess-players").url)
      case _ =>
        postForm(cls := "inline", action := routes.Team.join(t.id))(
          submitButton(cls := "button button-green")(trt.joinTeam())
        )

  private def joinAt(url: String)(using Context) =
    a(cls := "button button-green", href := url)(trt.joinTeam())
