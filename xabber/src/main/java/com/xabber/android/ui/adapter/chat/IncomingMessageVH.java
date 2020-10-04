package com.xabber.android.ui.adapter.chat;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.StyleRes;

import com.bumptech.glide.Glide;
import com.xabber.android.R;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.avatar.AvatarManager;
import com.xabber.android.data.extension.muc.MUCManager;
import com.xabber.android.data.groupchat.GroupchatUser;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.utils.Utils;

import org.jxmpp.jid.parts.Resourcepart;

public class IncomingMessageVH  extends FileMessageVH {

    public ImageView avatar;
    public ImageView avatarBackground;
    private BindListener listener;

    public interface BindListener {
        void onBind(MessageItem message);
    }

    IncomingMessageVH(View itemView, MessageClickListener messageListener,
                      MessageLongClickListener longClickListener,
                      FileListener fileListener, BindListener listener, @StyleRes int appearance) {
        super(itemView, messageListener, longClickListener, fileListener, appearance);
        avatar = itemView.findViewById(R.id.avatar);
        avatarBackground = itemView.findViewById(R.id.avatarBackground);
        this.listener = listener;
    }

    public void bind(final MessageItem messageItem, MessagesAdapter.MessageExtraData extraData) {
        super.bind(messageItem, extraData);

        Context context = extraData.getContext();
        boolean needTail = extraData.isNeedTail();

        // setup ARCHIVED icon
        //statusIcon.setVisibility(messageItem.isReceivedFromMessageArchive() ? View.VISIBLE : View.GONE);
        statusIcon.setVisibility(View.GONE);

        // setup FORWARDED
        boolean haveForwarded = messageItem.haveForwardedMessages();
        if (haveForwarded) {
            setupForwarded(messageItem, extraData);

            LinearLayout.LayoutParams forwardedParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

            forwardedParams.setMargins(
                    Utils.dipToPx(needTail ? 11f : 12f, context),
                    Utils.dipToPx(2f, context),
                    Utils.dipToPx(1f, context),
                    Utils.dipToPx(0f, context));

            forwardLayout.setLayoutParams(forwardedParams);
        } else forwardLayout.setVisibility(View.GONE);

        // setup BACKGROUND
        Drawable balloonDrawable = context.getResources().getDrawable(
                haveForwarded ? (needTail ? R.drawable.fwd_in : R.drawable.fwd)
                            : (needTail ? R.drawable.msg_in : R.drawable.msg));
        Drawable shadowDrawable = context.getResources().getDrawable(
                haveForwarded ? (needTail ? R.drawable.fwd_in_shadow : R.drawable.fwd_shadow)
                            : (needTail ? R.drawable.msg_in_shadow : R.drawable.msg_shadow));
        shadowDrawable.setColorFilter(context.getResources().getColor(R.color.black), PorterDuff.Mode.MULTIPLY);
        messageBalloon.setBackgroundDrawable(balloonDrawable);
        messageShadow.setBackgroundDrawable(shadowDrawable);

        // setup BALLOON margins
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        layoutParams.setMargins(
                Utils.dipToPx(needTail ? 2f : 11f, context),
                Utils.dipToPx(haveForwarded ? 0f : 2f, context),
                Utils.dipToPx(0f, context),
                Utils.dipToPx(2f, context));
        messageShadow.setLayoutParams(layoutParams);

        // setup MESSAGE padding
        messageBalloon.setPadding(
                Utils.dipToPx(needTail ? 20f : 12f, context),
                Utils.dipToPx(8f, context),
                Utils.dipToPx(12f, context),
                Utils.dipToPx(8f, context));

        // setup BACKGROUND COLOR
        setUpMessageBalloonBackground(messageBalloon, extraData.getColorStateList());

        setUpAvatar(context, extraData.getGroupchatUser(), messageItem,
                extraData.isMuc(), extraData.getUsername(), needTail);

        // hide empty message
        if (messageItem.getText().trim().isEmpty()
                && !messageItem.haveForwardedMessages()
                && !messageItem.haveAttachments()) {
            messageBalloon.setVisibility(View.GONE);
            messageTime.setVisibility(View.GONE);
            avatar.setVisibility(View.GONE);
            avatarBackground.setVisibility(View.GONE);
            LogManager.w(this, "Empty message! Hidden, but need to correct");
        } else {
            messageBalloon.setVisibility(View.VISIBLE);
            messageTime.setVisibility(View.VISIBLE);
        }

        itemView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (listener != null) listener.onBind(messageItem);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                unsubscribeAll();
            }
        });
    }

    private void setUpAvatar(Context context, GroupchatUser groupchatUser, MessageItem messageItem,
                             boolean isMUC, String userName, boolean needTail) {
        boolean needAvatar = isMUC ? SettingsManager.chatsShowAvatarsMUC() : SettingsManager.chatsShowAvatars();
        // for new groupchats (0GGG)
        if (groupchatUser != null && SettingsManager.chatsShowAvatarsMUC()) needAvatar = true;

        if (!needAvatar) {
            avatar.setVisibility(View.GONE);
            avatarBackground.setVisibility(View.GONE);
            return;
        }

        if (!needTail) {
            avatar.setVisibility(View.INVISIBLE);
            avatarBackground.setVisibility(View.INVISIBLE);
            return;
        }

        avatar.setVisibility(View.VISIBLE);
        avatarBackground.setVisibility(View.VISIBLE);

        //groupchat avatar
        if (groupchatUser != null) {
            Drawable placeholder;
            try {
                UserJid userJid = UserJid.from(messageItem.getUser().getJid().toString() + "/" + groupchatUser.getNickname());
                placeholder = AvatarManager.getInstance().getOccupantAvatar(userJid, groupchatUser.getNickname());
            } catch (UserJid.UserJidCreateException e) {
               placeholder = AvatarManager.getInstance()
                       .generateDefaultAvatar(groupchatUser.getNickname(), groupchatUser.getNickname());
            }
            Glide.with(context)
                    .load(groupchatUser.getAvatar())
                    .centerCrop()
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(avatar);
            return;
        }

        final UserJid user = messageItem.getUser();
        final AccountJid account = messageItem.getAccount();
        final Resourcepart resource = messageItem.getResource();

        if (!isMUC) avatar.setImageDrawable(AvatarManager.getInstance().getUserAvatarForContactList(user, userName));
        else {
            if ((MUCManager.getInstance()
                    .getNickname(account, user.getJid().asEntityBareJidIfPossible())
                    .equals(resource))) {
                avatar.setImageDrawable(AvatarManager.getInstance().getAccountAvatar(account));
            } else {
                if (resource.equals(Resourcepart.EMPTY)) {
                    avatar.setImageDrawable(AvatarManager.getInstance().getRoomAvatarForContactList(user));
                } else {

                    String nick = resource.toString();
                    UserJid userJid = null;

                    try {
                        userJid = UserJid.from(user.getJid().toString() + "/" + resource.toString());
                        avatar.setImageDrawable(AvatarManager.getInstance()
                                .getOccupantAvatar(userJid, nick));

                    } catch (UserJid.UserJidCreateException e) {
                        LogManager.exception(this, e);
                        avatar.setImageDrawable(AvatarManager.getInstance()
                                .generateDefaultAvatar(nick, nick));
                    }
                }
            }
        }
    }
}
