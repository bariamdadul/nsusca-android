package com.xabber.android.ui.adapter.chat;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.amulyakhare.textdrawable.util.ColorGenerator;
import com.xabber.android.R;
import com.xabber.android.data.database.MessageDatabaseManager;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.extension.otr.OTRManager;
import com.xabber.android.data.groupchat.GroupchatUser;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.ui.color.ColorManager;
import com.xabber.android.ui.fragment.ChatFragment;
import com.xabber.android.ui.text.ClickTagHandler;
import com.xabber.android.ui.widget.CorrectlyMeasuringTextView;
import com.xabber.android.utils.StringUtils;

import java.util.Arrays;
import java.util.Date;

import io.realm.RealmResults;
import io.realm.Sort;

public class MessageVH extends BasicMessageVH implements View.OnClickListener, View.OnLongClickListener {

    private static final String LOG_TAG = MessageVH.class.getSimpleName();
    private MessageClickListener listener;
    private MessageLongClickListener longClickListener;

    TextView tvFirstUnread;
    TextView tvDate;
    TextView messageTime;
    TextView messageHeader;
    TextView messageNotDecrypted;
    View messageBalloon;
    View messageShadow;
    ImageView statusIcon;
    ImageView ivEncrypted;
    String messageId;
    View forwardLayout;
    View forwardLeftBorder;

    public interface MessageClickListener {
        void onMessageClick(View caller, int position);
    }

    public interface MessageLongClickListener {
        void onLongMessageClick(int position);
    }

    public MessageVH(View itemView, MessageClickListener listener,
                     MessageLongClickListener longClickListener, @StyleRes int appearance) {
        super(itemView, appearance);
        this.listener = listener;
        this.longClickListener = longClickListener;

        tvFirstUnread = itemView.findViewById(R.id.tvFirstUnread);
        tvDate = itemView.findViewById(R.id.tvDate);
        messageTime = itemView.findViewById(R.id.message_time);
        messageHeader = itemView.findViewById(R.id.message_header);
        messageNotDecrypted = itemView.findViewById(R.id.message_not_decrypted);
        messageBalloon = itemView.findViewById(R.id.message_balloon);
        messageShadow = itemView.findViewById(R.id.message_shadow);
        statusIcon = itemView.findViewById(R.id.message_status_icon);
        ivEncrypted = itemView.findViewById(R.id.message_encrypted_icon);
        forwardLayout = itemView.findViewById(R.id.forwardLayout);
        forwardLeftBorder = itemView.findViewById(R.id.forwardLeftBorder);

        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(this);
    }

    public void bind(MessageItem messageItem, MessagesAdapter.MessageExtraData extraData) {
        if (extraData.isMuc()) {
            messageHeader.setText(messageItem.getResource());
            messageHeader.setTextColor(ColorManager.changeColor(
                    ColorGenerator.MATERIAL.getColor(messageItem.getResource()), 0.8f));
            messageHeader.setVisibility(View.VISIBLE);
        } else {
            messageHeader.setVisibility(View.GONE);
        }

        // groupchat
        if (extraData.getGroupchatUser() != null) {
            GroupchatUser user = extraData.getGroupchatUser();
            messageHeader.setText(user.getNickname());
            messageHeader.setTextColor(ColorManager.changeColor(
                    ColorGenerator.MATERIAL.getColor(user.getNickname()), 0.8f));
            messageHeader.setVisibility(View.VISIBLE);
        }

        if (messageItem.isEncrypted()) {
            ivEncrypted.setVisibility(View.VISIBLE);
        } else {
            ivEncrypted.setVisibility(View.GONE);
        }

        // Added .concat("&zwj;") and .concat(String.valueOf(Character.MIN_VALUE)
        // to avoid click by empty space after ClickableSpan
        if (messageItem.getMarkupText() != null && !messageItem.getMarkupText().isEmpty())
            messageText.setText(Html.fromHtml(
                    messageItem.getMarkupText().replace("\n", "<br/>").concat("&zwj;"),
                    null, new ClickTagHandler(extraData.getContext(),
                    extraData.getMentionColor())), TextView.BufferType.SPANNABLE);
        else messageText.setText(messageItem.getText().concat(String.valueOf(Character.MIN_VALUE)));
        if (OTRManager.getInstance().isEncrypted(messageItem.getText())) {
            if (extraData.isShowOriginalOTR())
                messageText.setVisibility(View.VISIBLE);
            else messageText.setVisibility(View.GONE);
            messageNotDecrypted.setVisibility(View.VISIBLE);
        } else {
            messageText.setVisibility(View.VISIBLE);
            messageNotDecrypted.setVisibility(View.GONE);
        }
        messageText.setMovementMethod(CorrectlyMeasuringTextView.LocalLinkMovementMethod.getInstance());

        String time = StringUtils.getTimeText(new Date(messageItem.getTimestamp()));

        Long delayTimestamp = messageItem.getDelayTimestamp();
        if (delayTimestamp != null) {
            String delay = extraData.getContext().getString(messageItem.isIncoming() ? R.string.chat_delay : R.string.chat_typed,
                    StringUtils.getTimeText(new Date(delayTimestamp)));
            time += " (" + delay + ")";
        }

        messageTime.setText(time);

        // setup UNREAD
        if (tvFirstUnread != null)
            tvFirstUnread.setVisibility(extraData.isUnread() ? View.VISIBLE : View.GONE);

        // setup DATE
        if (tvDate != null) {
            if (extraData.isNeedDate()) {
                tvDate.setText(StringUtils.getDateStringForMessage(messageItem.getTimestamp()));
                tvDate.setVisibility(View.VISIBLE);
            } else tvDate.setVisibility(View.GONE);
        }

        // set DATE alpha
        if (tvDate != null && extraData.isNeedDate() && extraData.getAnchorHolder() != null) {
            final MessagesAdapter.MessageExtraData lExtraData = extraData;
            tvDate.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    /** Work only with
                     *  @see ChatFragment#updateTopDateIfNeed()
                     *  called in recyclerView.onScrolled */
                    setDateAlpha(tvDate, lExtraData.getAnchorHolder().getAnchor());
                }
            });
        }

        // setup CHECKED
        if (extraData.isChecked()) itemView.setBackgroundColor(extraData.getContext().getResources()
                .getColor(R.color.unread_messages_background));
        else itemView.setBackgroundDrawable(null);
    }

    protected void setupForwarded(MessageItem messageItem, MessagesAdapter.MessageExtraData extraData) {
        String[] forwardedIDs = messageItem.getForwardedIdsAsArray();
        if (!Arrays.asList(forwardedIDs).contains(null)) {
            RealmResults<MessageItem> forwardedMessages =
                    MessageDatabaseManager.getInstance().getRealmUiThread().where(MessageItem.class)
                            .in(MessageItem.Fields.UNIQUE_ID, forwardedIDs)
                            .findAllSorted(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);

            if (forwardedMessages.size() > 0) {
                RecyclerView recyclerView = forwardLayout.findViewById(R.id.recyclerView);
                ForwardedAdapter adapter = new ForwardedAdapter(forwardedMessages, extraData);
                recyclerView.setLayoutManager(new LinearLayoutManager(extraData.getContext()));
                recyclerView.setAdapter(adapter);
                forwardLayout.setBackgroundColor(ColorManager.getColorWithAlpha(R.color.forwarded_background_color, 0.2f));
                forwardLeftBorder.setBackgroundColor(extraData.getAccountMainColor());
                forwardLayout.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onClick(View v) {
        int adapterPosition = getAdapterPosition();
        if (adapterPosition == RecyclerView.NO_POSITION) {
            LogManager.w(LOG_TAG, "onClick: no position");
            return;
        }
        listener.onMessageClick(messageBalloon, adapterPosition);
    }

    @Override
    public boolean onLongClick(View v) {
        int adapterPosition = getAdapterPosition();
        if (adapterPosition == RecyclerView.NO_POSITION) {
            LogManager.w(LOG_TAG, "onClick: no position");
            return false;
        }
        longClickListener.onLongMessageClick(adapterPosition);
        return true;
    }

    protected void setUpMessageBalloonBackground(View view, ColorStateList colorList) {
        final Drawable originalBackgroundDrawable = view.getBackground();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            originalBackgroundDrawable.setTintList(colorList);

        } else {
            Drawable wrapDrawable = DrawableCompat.wrap(originalBackgroundDrawable);
            DrawableCompat.setTintList(wrapDrawable, colorList);

            int pL = view.getPaddingLeft();
            int pT = view.getPaddingTop();
            int pR = view.getPaddingRight();
            int pB = view.getPaddingBottom();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                view.setBackground(wrapDrawable);
            else view.setBackgroundDrawable(wrapDrawable);
            view.setPadding(pL, pT, pR, pB);
        }

    }

    private void setDateAlpha(View viewDate, View viewAnchor) {
        if (viewDate != null && viewAnchor != null) {
            int specialCoordinates[] = new int[2];
            int titleCoordinates[] = new int[2];
            viewAnchor.getLocationOnScreen(titleCoordinates);
            viewDate.getLocationOnScreen(specialCoordinates);
            int deltaY = titleCoordinates[1] - specialCoordinates[1];
            if (deltaY < 0) deltaY *= -1;

            int total = viewAnchor.getMeasuredHeight();
            int step = total / 100;
            if (step == 0) step = 1;

            if (deltaY < total * 2) {
                if (deltaY < total) viewDate.setAlpha(0);
                else viewDate.setAlpha((float) (deltaY - total / step)/100);
            } else viewDate.setAlpha(1);
        }
    }
}
