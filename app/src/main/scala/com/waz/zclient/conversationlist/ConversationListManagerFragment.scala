/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.conversationlist

import java.util

import android.content.Intent
import android.os.Bundle
import android.support.v4.app.{Fragment, FragmentManager}
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.FrameLayout
import com.waz.ZLog
import com.waz.ZLog.{verbose, warn}
import com.waz.ZLog.ImplicitTag._
import com.waz.api._
import com.waz.model.ConversationData.ConversationType
import com.waz.model.sync.SyncCommand._
import com.waz.model.{ConvId, ConversationData, UserId}
import com.waz.service.ZMessaging
import com.waz.sync.SyncRequestServiceImpl.SyncMatcher
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.common.controllers.{SoundController, ThemeController, UserAccountsController}
import com.waz.zclient.controllers.calling.ICallingController
import com.waz.zclient.controllers.camera.ICameraController
import com.waz.zclient.controllers.confirmation._
import com.waz.zclient.controllers.currentfocus.IFocusController
import com.waz.zclient.controllers.navigation.{INavigationController, NavigationControllerObserver, Page}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.core.stores.connect.IConnectStore
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.messages.UsersController
import com.waz.zclient.pages.main.connect.{BlockedUserProfileFragment, ConnectRequestLoadMode, PendingConnectRequestManagerFragment, SendConnectRequestFragment}
import com.waz.zclient.pages.main.conversation.controller.{ConversationScreenControllerObserver, IConversationScreenController}
import com.waz.zclient.pages.main.participants.dialog.ParticipantsDialogFragment
import com.waz.zclient.pages.main.participants.{OptionsMenuControl, OptionsMenuFragment}
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController.Destination
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController.Destination._
import com.waz.zclient.pages.main.pickuser.controller.{IPickUserController, PickUserControllerScreenObserver}
import com.waz.zclient.pages.main.profile.camera.{CameraContext, CameraFragment}
import com.waz.zclient.ui.animation.interpolators.penner.{Expo, Quart}
import com.waz.zclient.ui.optionsmenu.{OptionsMenu, OptionsMenuItem}
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.usersearch.PickUserFragment
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{LayoutSpec, RichView}
import com.waz.zclient.views.LoadingIndicatorView
import com.waz.zclient.views.menus.ConfirmationMenu
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

class ConversationListManagerFragment extends Fragment
  with FragmentHelper
  with PickUserControllerScreenObserver
  with PickUserFragment.Container
  with NavigationControllerObserver
  with ConversationListFragment.Container
  with CameraFragment.Container
  with ConversationScreenControllerObserver
  with ConfirmationObserver
  with OnBackPressedListener
  with OptionsMenuFragment.Container
  with SendConnectRequestFragment.Container
  with BlockedUserProfileFragment.Container
  with ParticipantsDialogFragment.Container
  with PendingConnectRequestManagerFragment.Container {

  import ConversationListManagerFragment._
  import Threading.Implicits.Background

  implicit lazy val context = getContext

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val userAccounts = inject[UserAccountsController]
  lazy val users        = inject[UsersController]
  lazy val themes       = inject[ThemeController]
  lazy val sounds       = inject[SoundController]

  lazy val convController          = inject[ConversationController]
  lazy val accentColor             = inject[AccentColorController]
  lazy val pickUserController      = inject[IPickUserController]
  lazy val navController           = inject[INavigationController]
  lazy val convScreenController    = inject[IConversationScreenController]
  lazy val confirmationController  = inject[IConfirmationController]
  lazy val cameraController        = inject[ICameraController]
  lazy val focusController         = inject[IFocusController]
  lazy val callingController       = inject[ICallingController]

  private var startUiLoadingIndicator: LoadingIndicatorView = _
  private var listLoadingIndicator   : LoadingIndicatorView = _
  private var mainContainer          : FrameLayout          = _
  private var optionsMenuControl     : OptionsMenuControl   = _
  private var confirmationMenu       : ConfirmationMenu     = _

  lazy val hasConvs = for {
    z          <- zms
    convs      <- z.convsContent.conversationsSignal.throttle(ConvListUpdateThrottling )
  } yield !convs.conversations.exists(EstablishedListFilter)

  lazy val animationType = {
    import LoadingIndicatorView._
    hasConvs.map {
      case true => InfiniteLoadingBar
      case _    => Spinner
    }
  }

  private def stripToConversationList() =
    if (!LayoutSpec.isTablet(getActivity)) {
      pickUserController.hideUserProfile() // Hide possibly open self profile
      if (pickUserController.hidePickUser(getCurrentPickerDestination)) navController.setLeftPage(Page.CONVERSATION_LIST, Tag) // Hide possibly open start ui
    }

  private def animateOnIncomingCall() = {
    Option(getView).foreach {
      _.animate
        .alpha(0)
        .setInterpolator(new Quart.EaseOut)
        .setDuration(getInt(R.integer.calling_animation_duration_medium))
        .start()
    }

    CancellableFuture.delay(getInt(R.integer.calling_animation_duration_long).millis).map { _ =>
      pickUserController.hidePickUserWithoutAnimations(getCurrentPickerDestination)
      Option(getView).foreach(_.setAlpha(1))
    }
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) =
    returning(inflater.inflate(R.layout.fragment_conversation_list_manager, container, false)) { view =>
      mainContainer           = findById(view, R.id.fl__conversation_list_main)
      startUiLoadingIndicator = findById(view, R.id.liv__conversations__loading_indicator)
      listLoadingIndicator    = findById(view, R.id.lbv__conversation_list__loading_indicator)
      confirmationMenu        = returning(findById[ConfirmationMenu](view, R.id.cm__confirm_action_light)) { v =>
        v.setVisible(false)
        v.resetFullScreenPadding()
      }

      optionsMenuControl = new OptionsMenuControl

      if (savedInstanceState == null) {
        val fm = getChildFragmentManager
        import pickUserController._
        // When re-starting app to open into specific page, child fragments may exist despite savedInstanceState == null
        if (isShowingUserProfile) hideUserProfile()
        if (isShowingPickUser(CONVERSATION_LIST)) {
          hidePickUser(CONVERSATION_LIST)
          Option(fm.findFragmentByTag(PickUserFragment.TAG)).foreach { f =>
            fm.popBackStack(PickUserFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
          }
        }
        fm.beginTransaction
          .add(R.id.fl__conversation_list_main, ConversationListFragment.newNormalInstance(), NormalConversationListFragment.TAG)
          .add(R.id.fl__conversation_list__settings_box, OptionsMenuFragment.newInstance(true), OptionsMenuFragment.TAG)
          .commit
      }

      (for {
        z        <- zms
        syncSate <- z.syncRequests.syncState(SyncMatchers).map(_.state)
        animType <- animationType
      } yield (syncSate, animType)).onUi { case (state, animType) =>
        import SyncState._
        state match {
          case SYNCING | WAITING => listLoadingIndicator.show(animType)
          case _                 => listLoadingIndicator.hide()
        }
      }

      convController.convChanged.map(_.requester).onUi { req =>
        import ConversationChangeRequester._

        req match {
          case START_CONVERSATION |
               START_CONVERSATION_FOR_CALL |
               START_CONVERSATION_FOR_VIDEO_CALL |
               START_CONVERSATION_FOR_CAMERA |
               INTENT =>
            stripToConversationList()

          case INCOMING_CALL =>
            stripToConversationList()
            animateOnIncomingCall()

          case _ => //
        }
      }

      accentColor.accentColor.onUi { c =>
        Option(startUiLoadingIndicator).foreach(_.setColor(c.getColor))
        Option(listLoadingIndicator).foreach(_.setColor(c.getColor))
      }
    }

  override def onShowPickUser(destination: Destination) =
    if (getCurrentPickerDestination != destination) onHidePickUser(getCurrentPickerDestination)
    else {
      import Page._
      navController.getCurrentLeftPage match {
        // TODO: START is set as left page on tablet, fix
        case START | CONVERSATION_LIST =>
          withFragmentOpt(PickUserFragment.TAG) {
            case Some(_: PickUserFragment) => // already showing
            case _ =>
              getChildFragmentManager.beginTransaction
                .setCustomAnimations(
                  R.anim.slide_in_from_bottom_pick_user,
                  R.anim.open_new_conversation__thread_list_out,
                  R.anim.open_new_conversation__thread_list_in,
                  R.anim.slide_out_to_bottom_pick_user)
                .replace(R.id.fl__conversation_list_main, PickUserFragment.newInstance(addToConversation = false, null), PickUserFragment.TAG)
                .addToBackStack(PickUserFragment.TAG)
                .commit
          }
        case _ => //
      }
      navController.setLeftPage(Page.PICK_USER, Tag)
  }

  override def onHidePickUser(destination: Destination) =
    if (destination == getCurrentPickerDestination) {
      val page = navController.getCurrentLeftPage
      import Page._

      def hide() = {
        getChildFragmentManager.popBackStackImmediate(PickUserFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        KeyboardUtils.hideKeyboard(getActivity)
      }

      page match {
        case SEND_CONNECT_REQUEST | BLOCK_USER | PENDING_CONNECT_REQUEST =>
          pickUserController.hideUserProfile()
          hide()
        case PICK_USER => hide()
        case _ => //
      }

      navController.setLeftPage(Page.CONVERSATION_LIST, Tag)
      focusController.setFocus(IFocusController.CONVERSATION_CURSOR)
    }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = {
    super.onActivityResult(requestCode, resultCode, data)
    getChildFragmentManager.getFragments.asScala.foreach(_.onActivityResult(requestCode, resultCode, data))
  }

  override def onShowUserProfile(userId: UserId, anchorView: View) =
    if (!pickUserController.isShowingUserProfile && LayoutSpec.isPhone(getActivity)) {
      import User.ConnectionStatus._

      def show(fragment: Fragment, tag: String): Unit = {
        getChildFragmentManager
          .beginTransaction
          .setCustomAnimations(
            R.anim.fragment_animation__send_connect_request__fade_in,
            R.anim.fragment_animation__send_connect_request__zoom_exit,
            R.anim.fragment_animation__send_connect_request__zoom_enter,
            R.anim.fragment_animation__send_connect_request__fade_out)
          .replace(R.id.fl__conversation_list__profile_overlay, fragment, tag)
          .addToBackStack(tag).commit

        togglePeoplePicker(false)
      }

      zms.head.flatMap(_.usersStorage.get(userId)).foreach {
        case Some(userData) => userData.connection match {
          case CANCELLED | UNCONNECTED =>
            if (!userData.isConnected) {
              show(SendConnectRequestFragment.newInstance(userId.str, IConnectStore.UserRequester.SEARCH), SendConnectRequestFragment.TAG)
              navController.setLeftPage(Page.SEND_CONNECT_REQUEST, Tag)
            }

          case PENDING_FROM_OTHER | PENDING_FROM_USER | IGNORED =>
            show(
              PendingConnectRequestManagerFragment.newInstance(userId.str, null, ConnectRequestLoadMode.LOAD_BY_USER_ID, IConnectStore.UserRequester.SEARCH),
              PendingConnectRequestManagerFragment.TAG
            )
            navController.setLeftPage(Page.PENDING_CONNECT_REQUEST, Tag)

          case BLOCKED =>
            show (
              BlockedUserProfileFragment.newInstance(userId.str, IConnectStore.UserRequester.SEARCH),
              BlockedUserProfileFragment.TAG
            )
            navController.setLeftPage(Page.PENDING_CONNECT_REQUEST, Tag)
          case _ => //
        }
        case _ => //
      } (Threading.Ui)
    }

  private def togglePeoplePicker(show: Boolean) = {
    if (show)
      mainContainer
        .animate
        .alpha(1)
        .scaleY(1)
        .scaleX(1)
        .setInterpolator(new Expo.EaseOut)
        .setDuration(getInt(R.integer.reopen_profile_source__animation_duration))
        .setStartDelay(getInt(R.integer.reopen_profile_source__delay))
        .start()
    else
      mainContainer
        .animate
        .alpha(0)
        .scaleY(2)
        .scaleX(2)
        .setInterpolator(new Expo.EaseIn)
        .setDuration(getInt(R.integer.reopen_profile_source__animation_duration))
        .setStartDelay(0)
        .start()
  }

  override def onHideUserProfile() = {
    // Profiles are handled in dialog on tablet
    if (LayoutSpec.isPhone(getActivity)) {
      navController.setLeftPage(Page.PICK_USER, Tag)
      getChildFragmentManager.popBackStackImmediate
      togglePeoplePicker(true)
    }
  }

  override def showIncomingPendingConnectRequest(conv: ConvId) = {
    verbose(s"showIncomingPendingConnectRequest $conv")
    pickUserController.hidePickUser(getCurrentPickerDestination)
    convController.selectConv(conv, ConversationChangeRequester.INBOX)
  }

  override def onSelectedUsers(us: util.List[UserId], requester: ConversationChangeRequester) = {
    pickUserController.hidePickUser(getCurrentPickerDestination)
    userAccounts.getConversationId(us.asScala.toSet).flatMap { conv =>
      convController.selectConv(conv, requester)
    }
  }

  override def getLoadingViewIndicator =
    startUiLoadingIndicator

  override def getCurrentPickerDestination =
    IPickUserController.Destination.CONVERSATION_LIST

  override def onPageVisible(page: Page) =
    if (page != Page.ARCHIVE && page != Page.CONVERSATION_MENU_OVER_CONVERSATION_LIST) closeArchive()

  override def showArchive() = {
    import Page._
    navController.getCurrentLeftPage match {
      case START | CONVERSATION_LIST =>
        withFragmentOpt(ArchiveListFragment.TAG) {
          case Some(_: ArchiveListFragment) => // already showing
          case _ =>
            getChildFragmentManager.beginTransaction
              .setCustomAnimations(
                R.anim.slide_in_from_bottom_pick_user,
                R.anim.open_new_conversation__thread_list_out,
                R.anim.open_new_conversation__thread_list_in,
                R.anim.slide_out_to_bottom_pick_user)
              .replace(R.id.fl__conversation_list_main, ConversationListFragment.newArchiveInstance(), ArchiveListFragment.TAG)
              .addToBackStack(ArchiveListFragment.TAG)
              .commit
        }
      case _ => //
    }
    navController.setLeftPage(ARCHIVE, Tag)
  }

  override def closeArchive() = {
    getChildFragmentManager.popBackStackImmediate(ArchiveListFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    if (navController.getCurrentLeftPage == Page.ARCHIVE) navController.setLeftPage(Page.CONVERSATION_LIST, Tag)
  }

  override def onStart() = {
    super.onStart()
    cameraController.addCameraActionObserver(this)
    pickUserController.addPickUserScreenControllerObserver(this)
    convScreenController.addConversationControllerObservers(this)
    navController.addNavigationControllerObserver(this)
    confirmationController.addConfirmationObserver(this)
  }

  override def onStop() = {
    cameraController. removeCameraActionObserver(this)
    pickUserController.removePickUserScreenControllerObserver(this)
    convScreenController.removeConversationControllerObservers(this)
    navController.removeNavigationControllerObserver(this)
    confirmationController.removeConfirmationObserver(this)
    super.onStop()
  }

  override def onViewStateRestored(savedInstanceState: Bundle) = {
    super.onViewStateRestored(savedInstanceState)
    import Page._
    navController.getCurrentLeftPage match { // TODO: START is set as left page on tablet, fix
      case PICK_USER =>
        pickUserController.showPickUser(IPickUserController.Destination.CONVERSATION_LIST)
      case BLOCK_USER | PENDING_CONNECT_REQUEST | SEND_CONNECT_REQUEST | COMMON_USER_PROFILE =>
        togglePeoplePicker(false)
      case _ => //
    }
  }

  override def onBackPressed = {
    if (closeMenu) true
    else {
      withFragmentOpt(PickUserFragment.TAG) {
        case Some(f: PickUserFragment) if f.onBackPressed => true
        case _ =>
          withFragmentOpt(ArchiveListFragment.TAG) {
            case Some(f: ArchiveListFragment) if f.onBackPressed() => true
            case _ if pickUserController.isShowingPickUser(getCurrentPickerDestination) =>
              pickUserController.hidePickUser(getCurrentPickerDestination)
              true
            case _ => false
          }
      }
    }
  }

  private def closeMenu =
    optionsMenuControl.close

  override def onRequestConfirmation(confirmationRequest: ConfirmationRequest, requester: Int) =
    if (LayoutSpec.isTablet(getActivity) && requester == IConfirmationController.CONVERSATION_LIST)
      confirmationMenu.onRequestConfirmation(confirmationRequest)

  override def onAcceptedConnectRequest(convId: ConvId) = {
    verbose(s"onAcceptedConnectRequest $convId")
    convController.selectConv(convId, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onAcceptedPendingOutgoingConnectRequest(conversation: ConvId) = {
    verbose(s"onAcceptedPendingOutgoingConnectRequest: $conversation")
    convController.selectConv(conversation, ConversationChangeRequester.CONNECT_REQUEST_ACCEPTED)
  }

  override def onUnblockedUser(restoredConversationWithUser: ConvId) = {
    pickUserController.hideUserProfile()
    verbose(s"onUnblockedUser $restoredConversationWithUser")
    convController.selectConv(restoredConversationWithUser, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onOptionMenuStateHasChanged(state: OptionsMenu.State) = {
    import OptionsMenu.State._
    state match {
      case OPENING if LayoutSpec.isPhone(getActivity) =>
        navController.setLeftPage(Page.CONVERSATION_MENU_OVER_CONVERSATION_LIST, Tag)
      case CLOSED if LayoutSpec.isPhone(getActivity) =>
        navController.setLeftPage(Page.CONVERSATION_LIST, Tag)
      case _ => //
    }
  }

  override def onShowConversationMenu(requester: Int, convId: ConvId, anchorView: View) = {
    import IConversationScreenController._
    requester match {
      case CONVERSATION_LIST_SWIPE | CONVERSATION_LIST_LONG_PRESS =>
        optionsMenuControl.createMenu(convId, requester, themes.optionsDarkTheme)
        optionsMenuControl.open()
      case _ => //
    }
  }

  override def onOptionsItemClicked(convId: ConvId, user: User, item: OptionsMenuItem) = {
    val userId = Option(user).map(_.getId).map(new UserId(_))
    if (userId.isEmpty) warn(s"onOptionsItemClicked with null user, convId: $convId")
    import OptionsMenuItem._
    item match {
      case ARCHIVE   => convController.archive(convId, archive = true)
      case UNARCHIVE => convController.archive(convId, archive = false)
      case SILENCE   => convController.setMuted(convId, muted = true)
      case UNSILENCE => convController.setMuted(convId, muted = false)
      case LEAVE     => leaveConversation(convId)
      case DELETE    => deleteConversation(convId)
      case BLOCK     => userId.foreach(showBlockConfirmation)
      case UNBLOCK   => zms.head.flatMap { zms =>
        userId match {
          case Some(uId) => zms.connection.unblockConnection(uId)
          case _         => Future.successful({})
        }
      }
      case CALL      => callConversation(convId)
      case PICTURE   => sendPictureToConversation(convId)
      case _ =>
    }
    closeMenu
  }

  override def dismissUserProfile() =
    pickUserController.hideUserProfile()

  override def onConnectRequestWasSentToUser() =
    pickUserController.hideUserProfile()

  override def dismissSingleUserProfile() =
    dismissUserProfile()

  private def leaveConversation(convId: ConvId) = {
    closeMenu
    val callback = new TwoButtonConfirmationCallback() {
      override def positiveButtonClicked(checkboxIsSelected: Boolean) = convController.leave(convId)
      override def negativeButtonClicked() = {}
      override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean) = {}
    }
    val header = getString(R.string.confirmation_menu__meta_remove)
    val text    = getString(R.string.confirmation_menu__meta_remove_text)
    val confirm = getString(R.string.confirmation_menu__confirm_leave)
    val cancel  = getString(R.string.confirmation_menu__cancel)
    val request = new ConfirmationRequest.Builder()
      .withHeader(header)
      .withMessage(text)
      .withPositiveButton(confirm)
      .withNegativeButton(cancel)
      .withConfirmationCallback(callback)
      .withWireTheme(themes.optionsDarkTheme)
      .build
    confirmationController.requestConfirmation(request, IConfirmationController.CONVERSATION_LIST)
    sounds.playAlert()
  }

  def deleteConversation(convId: ConvId) = {
    closeMenu

    convController.loadConv(convId).map {
      case Some(conv) =>
        val callback = new TwoButtonConfirmationCallback() {
          override def positiveButtonClicked(checkboxIsSelected: Boolean) = {}
          override def negativeButtonClicked() = {}
          override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean) = {
            if (confirmed) convController.delete(convId, checkboxIsSelected)
          }
        }

        val header  = getString(R.string.confirmation_menu__meta_delete)
        val text    = getString(R.string.confirmation_menu__meta_delete_text)
        val confirm = getString(R.string.confirmation_menu__confirm_delete)
        val cancel  = getString(R.string.confirmation_menu__cancel)
        val checkboxLabel = if (conv.convType == IConversation.Type.GROUP)
          getString(R.string.confirmation_menu__delete_conversation__checkbox__label)
        else ""
        val request = new ConfirmationRequest.Builder()
          .withHeader(header)
          .withMessage(text)
          .withPositiveButton(confirm)
          .withNegativeButton(cancel)
          .withConfirmationCallback(callback)
          .withCheckboxLabel(checkboxLabel)
          .withWireTheme(themes.optionsDarkTheme)
          .build
        confirmationController.requestConfirmation(request, IConfirmationController.CONVERSATION_LIST)
        sounds.playAlert()
      case _ => //
    } (Threading.Ui)
  }

  private def showBlockConfirmation(userId: UserId) = {
    (for {
      convId      <- userAccounts.getConversationId(Set(userId))
      curConvId   <- convController.currentConvId.head
      displayName <- users.displayNameString(userId).head
    } yield (convId, curConvId, displayName)).map {
      case (convId, curConvId, displayName) =>
        val callback = new TwoButtonConfirmationCallback() {
          override def positiveButtonClicked(checkboxIsSelected: Boolean) = {
            zms.head.flatMap(_.connection.blockConnection(userId)).map { _ =>
              if (convId == curConvId)
                convController.setCurrentConversationToNext(ConversationChangeRequester.BLOCK_USER)
            } (Threading.Ui)
          }
          override def negativeButtonClicked() = {}
          override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean) = {}
        }
        val header  = getString(R.string.confirmation_menu__block_header)
        val text    = getString(R.string.confirmation_menu__block_text_with_name, displayName)
        val confirm = getString(R.string.confirmation_menu__confirm_block)
        val cancel  = getString(R.string.confirmation_menu__cancel)
        val request = new ConfirmationRequest.Builder()
          .withHeader(header)
          .withMessage(text)
          .withPositiveButton(confirm)
          .withNegativeButton(cancel)
          .withConfirmationCallback(callback)
          .withWireTheme(themes.optionsDarkTheme)
          .build
        confirmationController.requestConfirmation(request, IConfirmationController.CONVERSATION_LIST)
        sounds.playAlert()
    } (Threading.Ui)
  }

  private def callConversation(convId: ConvId) = {
    verbose(s"callConversation $convId")
    convController.selectConv(convId, ConversationChangeRequester.CONVERSATION_LIST)
    callingController.startCall(false)
  }

  private def sendPictureToConversation(convId: ConvId) = {
    verbose(s"endPictureToConversation $convId")
    convController.selectConv(convId, ConversationChangeRequester.CONVERSATION_LIST)
    cameraController.openCamera(CameraContext.MESSAGE)
  }

  override def onShowParticipants(anchorView:             View,
                                  isSingleConversation:   Boolean,
                                  isMemberOfConversation: Boolean,
                                  showDeviceTabIfSingle:  Boolean) = {}

  override def onHideParticipants(backOrButtonPressed:      Boolean,
                                  hideByConversationChange: Boolean,
                                  isSingleConversation:     Boolean) = {}

  override def onShowEditConversationName(show: Boolean) = {}

  override def onHeaderViewMeasured(participantHeaderHeight: Int) = {}

  override def onScrollParticipantsList(verticalOffset: Int, scrolledToBottom: Boolean) = {}

  override def onShowUser(user: User) = {}

  override def onHideUser() = {}

  override def onAddPeopleToConversation() = {}

  override def onShowOtrClient(otrClient: OtrClient, user: User) = {}

  override def onShowCurrentOtrClient() = {}

  override def onHideOtrClient() = {}

  override def onShowLikesList(message: Message) = {}

  override def onBitmapSelected(imageAsset: ImageAsset, imageFromCamera: Boolean, cameraContext: CameraContext) = {}

  override def onCameraNotAvailable() = {}

  override def onOpenCamera(cameraContext: CameraContext) = {}

  override def onCloseCamera(cameraContext: CameraContext) = {}

  override def getOptionsMenuControl = optionsMenuControl

  override def showRemoveConfirmation(user: User) = {}

  override def onOpenUrl(url: String) = {}
}

object ConversationListManagerFragment {


  //TODO make a conversationsList controller or something
  lazy val SyncMatchers = Seq(SyncConversations, SyncSelf, SyncConnections).map(SyncMatcher(_, None))
  lazy val RegularListFilter: (ConversationData => Boolean) = { c => !c.hidden && !c.archived && c.convType != ConversationType.Incoming && c.convType != ConversationType.Self }
  lazy val EstablishedListFilter: (ConversationData => Boolean) = { c => RegularListFilter(c) && c.convType != ConversationType.WaitForConnection }

  lazy val ConvListUpdateThrottling = 250.millis

  val Tag = ConversationListManagerFragment.getClass.getSimpleName

  def newInstance() = new ConversationListManagerFragment()
}
