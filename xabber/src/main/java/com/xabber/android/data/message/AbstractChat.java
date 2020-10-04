/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.message;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.database.MessageDatabaseManager;
import com.xabber.android.data.database.messagerealm.Attachment;
import com.xabber.android.data.database.messagerealm.ForwardId;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.carbons.CarbonManager;
import com.xabber.android.data.extension.chat_markers.BackpressureMessageReader;
import com.xabber.android.data.extension.cs.ChatStateManager;
import com.xabber.android.data.extension.file.FileManager;
import com.xabber.android.data.extension.file.UriUtils;
import com.xabber.android.data.extension.httpfileupload.HttpFileUploadManager;
import com.xabber.android.data.extension.muc.MUCManager;
import com.xabber.android.data.extension.otr.OTRManager;
import com.xabber.android.data.extension.references.ReferenceElement;
import com.xabber.android.data.extension.references.ReferencesManager;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.chat.ChatManager;
import com.xabber.android.data.notification.MessageNotificationManager;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.data.roster.RosterCacheManager;
import com.xabber.android.utils.Utils;
import com.xabber.xmpp.sid.OriginIdElement;
import com.xabber.xmpp.sid.UniqStanzaHelper;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Message.Type;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.delay.packet.DelayInformation;
import org.jivesoftware.smackx.forward.packet.Forwarded;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Resourcepart;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmList;
import io.realm.RealmQuery;
import io.realm.RealmResults;
import io.realm.Sort;

/**
 * Chat instance.
 *
 * @author alexander.ivanov
 */
public abstract class AbstractChat extends BaseEntity implements RealmChangeListener<RealmResults<MessageItem>> {

    /**
     * Number of messages from history to be shown for context purpose.
     */
    public static final int PRELOADED_MESSAGES = 50;

    /**
     * Whether chat is open and should be displayed as active chat.
     */
    protected boolean active;
    /**
     * Whether changes in status should be record.
     */
    private boolean trackStatus;
    /**
     * Whether user never received notifications from this chat.
     */
    private boolean firstNotification;

    /**
     * Current thread id.
     */
    private String threadId;

    private int lastPosition;
    private boolean archived;
    protected NotificationState notificationState;

    private Set<String> waitToMarkAsRead = new HashSet<>();

    private boolean isPrivateMucChat;
    private boolean isPrivateMucChatAccepted;

    private boolean isRemotePreviousHistoryCompletelyLoaded = false;

    private Date lastSyncedTime;
    private MessageItem lastMessage;
    private RealmResults<MessageItem> messages;
    private String lastMessageId = null;
    private boolean historyIsFull = false;
    private boolean historyRequestedAtStart = false;
    protected boolean isGroupchat = false;

    protected AbstractChat(@NonNull final AccountJid account, @NonNull final UserJid user, boolean isPrivateMucChat) {
        super(account, isPrivateMucChat ? user : user.getBareUserJid());
        threadId = StringUtils.randomString(12);
        active = false;
        trackStatus = false;
        firstNotification = true;
        this.isPrivateMucChat = isPrivateMucChat;
        isPrivateMucChatAccepted = false;
        notificationState = new NotificationState(NotificationState.NotificationMode.bydefault, 0);

        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getMessages();
            }
        });
    }

    public boolean isRemotePreviousHistoryCompletelyLoaded() {
        return isRemotePreviousHistoryCompletelyLoaded;
    }

    public void setRemotePreviousHistoryCompletelyLoaded(boolean remotePreviousHistoryCompletelyLoaded) {
        isRemotePreviousHistoryCompletelyLoaded = remotePreviousHistoryCompletelyLoaded;
    }

    public Date getLastSyncedTime() {
        return lastSyncedTime;
    }

    public void setLastSyncedTime(Date lastSyncedTime) {
        this.lastSyncedTime = lastSyncedTime;
    }


    public boolean isActive() {
        if (isPrivateMucChat && !isPrivateMucChatAccepted) {
            return false;
        }

        return active;
    }

    public void openChat() {
        active = true;
        trackStatus = true;
    }

    void closeChat() {
        active = false;
        firstNotification = true;
    }

    private String getAccountString() {
        return account.toString();
    }

    private String getUserString() {
        return user.toString();
    }

    public RealmResults<MessageItem> getMessages() {
        if (messages == null) {
            messages = MessageDatabaseManager.getChatMessages(
                    MessageDatabaseManager.getInstance().getRealmUiThread(),
                    account,
                    user);
            updateLastMessage();

            messages.addChangeListener(this);
        }

        return messages;
    }

    boolean isStatusTrackingEnabled() {
        return trackStatus;
    }

    /**
     * @return Target address for sending message.
     */
    @NonNull
    public abstract Jid getTo();

    /**
     * @return Message type to be assigned.
     */
    public abstract Type getType();

    /**
     * @return Whether user never received notifications from this chat. And
     * mark as received.
     */
    public boolean getFirstNotification() {
        boolean result = firstNotification;
        firstNotification = false;
        return result;
    }

    /**
     * @return Whether user should be notified about incoming messages in chat.
     */
    public boolean notifyAboutMessage() {
        if (notificationState.getMode().equals(NotificationState.NotificationMode.bydefault))
            return SettingsManager.eventsOnChat();
        if (notificationState.getMode().equals(NotificationState.NotificationMode.enabled))
            return true;
        else return false;
    }

    public void enableNotificationsIfNeed() {
        int currentTime = (int) (System.currentTimeMillis() / 1000L);
        NotificationState.NotificationMode mode = notificationState.getMode();

        if ((mode.equals(NotificationState.NotificationMode.snooze15m)
                && currentTime > notificationState.getTimestamp() + TimeUnit.MINUTES.toSeconds(15))
            || (mode.equals(NotificationState.NotificationMode.snooze1h)
                && currentTime > notificationState.getTimestamp() + TimeUnit.HOURS.toSeconds(1))
            || (mode.equals(NotificationState.NotificationMode.snooze2h)
                && currentTime > notificationState.getTimestamp() + TimeUnit.HOURS.toSeconds(2))
            || (mode.equals(NotificationState.NotificationMode.snooze1d)
                && currentTime > notificationState.getTimestamp() + TimeUnit.DAYS.toSeconds(1))) {

            setNotificationStateOrDefault(new NotificationState(
                    NotificationState.NotificationMode.enabled, 0), true);
        }
    }

    abstract protected MessageItem createNewMessageItem(String text);

    /**
     * Creates new action.
     * @param resource can be <code>null</code>.
     * @param text     can be <code>null</code>.
     */
    public void newAction(Resourcepart resource, String text, ChatAction action, boolean fromMUC) {
        createAndSaveNewMessage(true, UUID.randomUUID().toString(), resource, text, null,
                action, null, null, true, false, false, false,
                null, null, null, null, null,
                fromMUC, false, null);
    }

    /**
     * Creates new message.
     * <p/>
     * Any parameter can be <code>null</code> (except boolean values).
     *
     * @param resource       Contact's resource or nick in conference.
     * @param text           message.
     * @param action         Informational message.
     * @param delayTimestamp Time when incoming message was sent or outgoing was created.
     * @param incoming       Incoming message.
     * @param notify         Notify user about this message when appropriated.
     * @param encrypted      Whether encrypted message in OTR chat was received.
     * @param offline        Whether message was received from server side offline storage.
     * @return
     */
    protected void createAndSaveNewMessage(boolean ui, String uid, Resourcepart resource, String text,
                   String markupText, final ChatAction action, final Date timestamp,
                   final Date delayTimestamp, final boolean incoming, boolean notify,
                   final boolean encrypted, final boolean offline, final String stanzaId,
                   final String originalStanza, final String parentMessageId, final String originalFrom,
                   final RealmList<ForwardId> forwardIds, boolean fromMUC, boolean fromMAM, String groupchatUserId) {

        final MessageItem messageItem = createMessageItem(uid, resource, text, markupText, action,
                timestamp, delayTimestamp, incoming, notify, encrypted, offline, stanzaId, null,
                originalStanza, parentMessageId, originalFrom, forwardIds, fromMUC, fromMAM, groupchatUserId);

        saveMessageItem(ui, messageItem);
        //EventBus.getDefault().post(new NewMessageEvent());
    }

    protected void createAndSaveFileMessage(boolean ui, String uid, Resourcepart resource, String text,
                    String markupText, final ChatAction action, final Date timestamp,
                    final Date delayTimestamp, final boolean incoming, boolean notify,
                    final boolean encrypted, final boolean offline, final String stanzaId,
                    RealmList<Attachment> attachments, final String originalStanza,
                    final String parentMessageId, final String originalFrom, boolean fromMUC,
                    boolean fromMAM, String groupchatUserId) {

        final MessageItem messageItem = createMessageItem(uid, resource, text, markupText, action,
                timestamp, delayTimestamp, incoming, notify, encrypted, offline, stanzaId, attachments,
                originalStanza, parentMessageId, originalFrom, null, fromMUC, fromMAM, groupchatUserId);

        saveMessageItem(ui, messageItem);
        //EventBus.getDefault().post(new NewMessageEvent());
    }

    public void saveMessageItem(boolean ui, final MessageItem messageItem) {
        if (ui) BackpressureMessageSaver.getInstance().saveMessageItem(messageItem);
        else {
            final long startTime = System.currentTimeMillis();
            Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
            realm.executeTransactionAsync(new Realm.Transaction() {
                @Override
                public void execute(Realm realm) {
                    realm.copyToRealm(messageItem);
                    LogManager.d("REALM", Thread.currentThread().getName()
                            + " save message item: " + (System.currentTimeMillis() - startTime));
                    EventBus.getDefault().post(new NewMessageEvent());
                }
            });
        }
    }

    protected MessageItem createMessageItem(Resourcepart resource, String text,
                        String markupText, ChatAction action,
                        Date delayTimestamp, boolean incoming, boolean notify, boolean encrypted,
                        boolean offline, String stanzaId, RealmList<Attachment> attachments,
                        String originalStanza, String parentMessageId, String originalFrom,
                        RealmList<ForwardId> forwardIds, boolean fromMUC) {

        return createMessageItem(UUID.randomUUID().toString(), resource,  text,  markupText, action,
                null, delayTimestamp,  incoming,  notify,  encrypted, offline,  stanzaId, attachments,
                 originalStanza,  parentMessageId,  originalFrom, forwardIds, fromMUC, false, null);
    }

    protected MessageItem createMessageItem(String uid, Resourcepart resource, String text,
                        String markupText, ChatAction action, Date timestamp,
                        Date delayTimestamp, boolean incoming, boolean notify, boolean encrypted,
                        boolean offline, String stanzaId, RealmList<Attachment> attachments,
                        String originalStanza, String parentMessageId, String originalFrom,
                        RealmList<ForwardId> forwardIds, boolean fromMUC, boolean fromMAM, String groupchatUserId) {

        final boolean visible = MessageManager.getInstance().isVisibleChat(this);
        boolean read = !incoming;
        boolean send = incoming;
        if (action == null && text == null) {
            throw new IllegalArgumentException();
        }
        if (text == null) {
            text = " ";
        }
        if (action != null) {
            read = true;
            send = true;
        }

        if (timestamp == null) timestamp = new Date();

        if (text.trim().isEmpty() && (forwardIds == null || forwardIds.isEmpty())
            && (attachments == null || attachments.isEmpty())) {
            notify = false;
        }

        if (notify || !incoming) {
            openChat();
        }
        if (!incoming) {
            notify = false;
        }

        if (isPrivateMucChat) {
            if (!isPrivateMucChatAccepted) {
                notify = false;
            }
        }

        MessageItem messageItem = new MessageItem(uid);

        messageItem.setAccount(account);
        messageItem.setUser(user);

        if (resource == null) {
            messageItem.setResource(Resourcepart.EMPTY);
        } else {
            messageItem.setResource(resource);
        }

        if (action != null) {
            messageItem.setAction(action.toString());
        }
        messageItem.setText(text);
        if (markupText != null) messageItem.setMarkupText(markupText);
        messageItem.setTimestamp(timestamp.getTime());
        if (delayTimestamp != null) {
            messageItem.setDelayTimestamp(delayTimestamp.getTime());
        }
        messageItem.setIncoming(incoming);
        messageItem.setRead(fromMAM || read);
        messageItem.setSent(send);
        messageItem.setEncrypted(encrypted);
        messageItem.setOffline(offline);
        messageItem.setFromMUC(fromMUC);
        messageItem.setStanzaId(stanzaId);
        if (attachments != null) messageItem.setAttachments(attachments);
        FileManager.processFileMessage(messageItem);

        // forwarding
        if (forwardIds != null) messageItem.setForwardedIds(forwardIds);
        messageItem.setOriginalStanza(originalStanza);
        messageItem.setOriginalFrom(originalFrom);
        messageItem.setParentMessageId(parentMessageId);

        // groupchat
        if (groupchatUserId != null) messageItem.setGroupchatUserId(groupchatUserId);

        // notification
        enableNotificationsIfNeed();
        if (notify && notifyAboutMessage() && !visible)
            NotificationManager.getInstance().onMessageNotification(messageItem);

        // remove notifications if get outgoing message with 2 sec delay
        if (!incoming) MessageNotificationManager.getInstance().removeChatWithTimer(account, user);

        // when getting new message, unarchive chat if chat not muted
        if (this.notifyAboutMessage())
            this.archived = false;

        // update last id in chat
        messageItem.setPreviousId(getLastMessageId());
        String id = messageItem.getArchivedId();
        if (id == null) id = messageItem.getStanzaId();
        setLastMessageId(id);

        return messageItem;
    }

    public String newFileMessage(final List<File> files, final List<Uri> uris) {
        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
        final String messageId = UUID.randomUUID().toString();

        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                RealmList<Attachment> attachments;
                if (files != null) attachments = attachmentsFromFiles(files);
                else attachments = attachmentsFromUris(uris);

                MessageItem messageItem = new MessageItem(messageId);
                messageItem.setAccount(account);
                messageItem.setUser(user);
                messageItem.setText("Sending files..");
                messageItem.setAttachments(attachments);
                messageItem.setTimestamp(System.currentTimeMillis());
                messageItem.setRead(true);
                messageItem.setSent(true);
                messageItem.setError(false);
                messageItem.setIncoming(false);
                messageItem.setInProgress(true);
                messageItem.setStanzaId(UUID.randomUUID().toString());
                realm.copyToRealm(messageItem);
            }
        });

        return messageId;
    }

    public RealmList<Attachment> attachmentsFromFiles(List<File> files) {
        RealmList<Attachment> attachments = new RealmList<>();
        for (File file : files) {
            Attachment attachment = new Attachment();
            attachment.setFilePath(file.getPath());
            attachment.setFileSize(file.length());
            attachment.setTitle(file.getName());
            attachment.setIsImage(FileManager.fileIsImage(file));
            attachment.setMimeType(HttpFileUploadManager.getMimeType(file.getPath()));
            attachment.setDuration((long) 0);

            if (attachment.isImage()) {
                HttpFileUploadManager.ImageSize imageSize =
                        HttpFileUploadManager.getImageSizes(file.getPath());
                attachment.setImageHeight(imageSize.getHeight());
                attachment.setImageWidth(imageSize.getWidth());
            }
            attachments.add(attachment);
        }
        return attachments;
    }

    public RealmList<Attachment> attachmentsFromUris(List<Uri> uris) {
        RealmList<Attachment> attachments = new RealmList<>();
        for (Uri uri : uris) {
            Attachment attachment = new Attachment();
            attachment.setTitle(UriUtils.getFullFileName(uri));
            attachment.setIsImage(UriUtils.uriIsImage(uri));
            attachment.setMimeType(UriUtils.getMimeType(uri));
            attachment.setDuration((long) 0);
            attachments.add(attachment);
        }
        return attachments;
    }

    /**
     * @return Whether chat accepts packets from specified user.
     */
    private boolean accept(UserJid jid) {
        return this.user.equals(jid);
    }

    @Nullable
    public synchronized MessageItem getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(MessageItem lastMessage) {
        this.lastMessage = lastMessage;
    }

    private void updateLastMessage() {
        Realm realm = MessageDatabaseManager.getInstance().getRealmUiThread();
        lastMessage = realm.where(MessageItem.class)
                .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                .equalTo(MessageItem.Fields.USER, user.toString())
                .isNull(MessageItem.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageItem.Fields.TEXT)
                .isNull(MessageItem.Fields.ACTION)
                .or()
                .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                .equalTo(MessageItem.Fields.USER, user.toString())
                .isNull(MessageItem.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageItem.Fields.TEXT)
                .equalTo(MessageItem.Fields.ACTION, ChatAction.available.toString())
                .findAllSorted(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING).last(null);
    }

    /**
     * @return Time of last message in chat. Can be <code>null</code>.
     */
    public Date getLastTime() {
        MessageItem lastMessage = getLastMessage();
        if (lastMessage != null) {
            return new Date(lastMessage.getTimestamp());
        } else {
            return null;
        }
    }

    public Message createMessagePacket(String body, String stanzaId) {
        Message message = createMessagePacket(body);
        if (stanzaId != null) message.setStanzaId(stanzaId);
        return message;
    }

    /**
     * @return New message packet to be sent.
     */
    public Message createMessagePacket(String body) {
        Message message = new Message();
        message.setTo(getTo());
        message.setType(getType());
        message.setBody(body);
        message.setThread(threadId);
        return message;
    }

    /**
     * Send stanza with data-references.
     */
    public Message createFileMessagePacket(String stanzaId, RealmList<Attachment> attachments, String body) {

        Message message = new Message();
        message.setTo(getTo());
        message.setType(getType());
        message.setThread(threadId);
        if (stanzaId != null) message.setStanzaId(stanzaId);

        StringBuilder builder = new StringBuilder(body);
        for (Attachment attachment : attachments) {
            StringBuilder rowBuilder = new StringBuilder();
            if (builder.length() > 0) rowBuilder.append("\n");
            rowBuilder.append(attachment.getFileUrl());

            int begin = getSizeOfEncodedChars(builder.toString());
            builder.append(rowBuilder);
            ReferenceElement reference = ReferencesManager.createMediaReferences(attachment,
                    begin, getSizeOfEncodedChars(builder.toString()) - 1);
            message.addExtension(reference);
        }

        message.setBody(builder);
        return message;
    }

    private int getSizeOfEncodedChars(String str) {
        return Utils.xmlEncode(str).toCharArray().length;
    }

    /**
     * Prepare text to be send.
     *
     * @return <code>null</code> if text shouldn't be send.
     */
    protected String prepareText(String text) {
        return text;
    }


    public void sendMessages() {
        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();

                RealmResults<MessageItem> messagesToSend = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                        .equalTo(MessageItem.Fields.USER, user.toString())
                        .equalTo(MessageItem.Fields.SENT, false)
                        .findAllSorted(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);

                realm.beginTransaction();

                for (final MessageItem messageItem : messagesToSend) {
                    if (messageItem.isInProgress()) continue;
                    if (!sendMessage(messageItem)) {
                        break;
                    }
                }
                realm.commitTransaction();

                realm.close();
            }
        });
    }

    protected boolean canSendMessage() {
        return true;
    }

    @SuppressWarnings("WeakerAccess")
    boolean sendMessage(MessageItem messageItem) {
        String text = prepareText(messageItem.getText());
        messageItem.setEncrypted(OTRManager.getInstance().isEncrypted(text));
        Long timestamp = messageItem.getTimestamp();

        Date currentTime = new Date(System.currentTimeMillis());
        Date delayTimestamp = null;

        if (timestamp != null) {
            if (currentTime.getTime() - timestamp > 60000) {
                delayTimestamp = currentTime;
            }
        }

        Message message = null;

        if (messageItem.haveAttachments()) {
            message = createFileMessagePacket(messageItem.getStanzaId(),
                    messageItem.getAttachments(), text);

        } else if (messageItem.haveForwardedMessages()) {
            Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
            RealmResults<MessageItem> items = realm.where(MessageItem.class)
                    .in(MessageItem.Fields.UNIQUE_ID, messageItem.getForwardedIdsAsArray()).findAll();

            List<ReferenceElement> references = new ArrayList<>();
            StringBuilder builder = new StringBuilder();
            if (items != null && !items.isEmpty()) {
                for (MessageItem item : items) {
                    String forward = ClipManager.createMessageTree(realm, item.getUniqueId()) + "\n";
                    int begin = getSizeOfEncodedChars(builder.toString());
                    builder.append(forward);
                    ReferenceElement reference = ReferencesManager.createForwardReference(item,
                            begin, getSizeOfEncodedChars(builder.toString()) - 1);
                    references.add(reference);
                }
            }
            builder.append(text);
            text = builder.toString();

            message = createMessagePacket(text, messageItem.getStanzaId());
            for (ReferenceElement element : references) {
                message.addExtension(element);
            }

        } else if (text != null) {
            message = createMessagePacket(text, messageItem.getStanzaId());
        }

        if (message != null) {
            ChatStateManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            CarbonManager.getInstance().updateOutgoingMessage(AbstractChat.this, message);
            message.addExtension(new OriginIdElement(messageItem.getStanzaId()));
            if (delayTimestamp != null) {
                message.addExtension(new DelayInformation(delayTimestamp));
            }

            final String messageId = messageItem.getUniqueId();
            try {
                StanzaSender.sendStanza(account, message, new StanzaListener() {
                    @Override
                    public void processStanza(Stanza packet) throws SmackException.NotConnectedException {
                        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
                        realm.executeTransaction(new Realm.Transaction() {
                                @Override
                                public void execute(Realm realm) {
                                    MessageItem acknowledgedMessage = realm
                                            .where(MessageItem.class)
                                            .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                                            .findFirst();

                                    if (acknowledgedMessage != null) {
                                        acknowledgedMessage.setAcknowledged(true);
                                    }
                                }
                            });
                        realm.close();
                    }
                });
            } catch (NetworkException e) {
                return false;
            }
        }

        if (message == null) {
            messageItem.setError(true);
            messageItem.setErrorDescription("Internal error: message is null");
        } else {
            message.setFrom(account.getFullJid());
            messageItem.setOriginalStanza(message.toXML().toString());
        }

        if (delayTimestamp != null) {
            messageItem.setDelayTimestamp(delayTimestamp.getTime());
        }
        if (messageItem.getTimestamp() == null) {
            messageItem.setTimestamp(currentTime.getTime());
        }
        messageItem.setSent(true);
        return true;
    }

    public String getThreadId() {
        return threadId;
    }

    /**
     * Update thread id with new value.
     *
     * @param threadId <code>null</code> if current value shouldn't be changed.
     */
    protected void updateThreadId(String threadId) {
        if (threadId == null) {
            return;
        }
        this.threadId = threadId;
    }

    /**
     * Processes incoming packet.
     *
     * @param userJid
     * @param packet
     * @return Whether packet was directed to this chat.
     */
    protected boolean onPacket(UserJid userJid, Stanza packet, boolean isCarbons) {
        return accept(userJid);
    }

    /**
     * Connection complete.f
     */
    protected void onComplete() {
    }

    /**
     * Disconnection occured.
     */
    protected void onDisconnect() {
        setLastMessageId(null);
    }

    public void setIsPrivateMucChatAccepted(boolean isPrivateMucChatAccepted) {
        this.isPrivateMucChatAccepted = isPrivateMucChatAccepted;
    }

    boolean isPrivateMucChat() {
        return isPrivateMucChat;
    }

    boolean isPrivateMucChatAccepted() {
        return isPrivateMucChatAccepted;
    }

    @Override
    public void onChange(RealmResults<MessageItem> messageItems) {
        updateLastMessage();
        RosterCacheManager.saveLastMessageToContact(
                MessageDatabaseManager.getInstance().getRealmUiThread(), lastMessage);
    }

    /** UNREAD MESSAGES */

    public String getFirstUnreadMessageId() {
        String id = null;
        RealmResults<MessageItem> results = getAllUnreadAscending();
        if (results != null && !results.isEmpty()) {
            MessageItem firstUnreadMessage = results.first();
            if (firstUnreadMessage != null)
                id = firstUnreadMessage.getUniqueId();
        }
        return id;
    }

    public int getUnreadMessageCount() {
        int unread = ((int) getAllUnreadQuery().count()) - waitToMarkAsRead.size();
        if (unread < 0) unread = 0;
        return unread;
    }

    public void approveRead(List<String> ids) {
        for (String id : ids) {
            waitToMarkAsRead.remove(id);
        }
        EventBus.getDefault().post(new MessageUpdateEvent(account, user));
    }

    public void markAsRead(String messageId, boolean trySendDisplay) {
        MessageItem message = MessageDatabaseManager.getInstance().getRealmUiThread()
                .where(MessageItem.class).equalTo(MessageItem.Fields.STANZA_ID, messageId).findFirst();
        if (message != null) executeRead(message, trySendDisplay);
    }

    public void markAsRead(MessageItem messageItem, boolean trySendDisplay) {
        waitToMarkAsRead.add(messageItem.getUniqueId());
        executeRead(messageItem, trySendDisplay);
    }

    public void markAsReadAll(boolean trySendDisplay) {
        RealmResults<MessageItem> results = getAllUnreadAscending();
        if (results != null && !results.isEmpty()) {
            for (MessageItem message : results) {
                waitToMarkAsRead.add(message.getUniqueId());
            }
            MessageItem lastMessage = results.last();
            if (lastMessage != null) executeRead(lastMessage, trySendDisplay);
        }
    }

    private void executeRead(MessageItem messageItem, boolean trySendDisplay) {
        EventBus.getDefault().post(new MessageUpdateEvent(account, user));
        BackpressureMessageReader.getInstance().markAsRead(messageItem, trySendDisplay);
    }

    private RealmQuery<MessageItem> getAllUnreadQuery() {
        return MessageDatabaseManager.getInstance().getRealmUiThread().where(MessageItem.class)
                .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                .equalTo(MessageItem.Fields.USER, user.toString())
                .isNull(MessageItem.Fields.PARENT_MESSAGE_ID)
                .isNotNull(MessageItem.Fields.TEXT)
                .equalTo(MessageItem.Fields.INCOMING, true)
                .equalTo(MessageItem.Fields.READ, false);
    }

    private RealmResults<MessageItem> getAllUnreadAscending() {
        return getAllUnreadQuery().findAllSorted(MessageItem.Fields.TIMESTAMP, Sort.ASCENDING);
    }

    /** ^ UNREAD MESSAGES ^ */

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived, boolean needSaveToRealm) {
        this.archived = archived;
        if (needSaveToRealm) ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public NotificationState getNotificationState() {
        return notificationState;
    }

    public void setNotificationState(NotificationState notificationState, boolean needSaveToRealm) {
        this.notificationState = notificationState;
        if (notificationState.getMode() == NotificationState.NotificationMode.disabled && needSaveToRealm)
            NotificationManager.getInstance().removeMessageNotification(account, user);
        if (needSaveToRealm) ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public void setNotificationStateOrDefault(NotificationState notificationState, boolean needSaveToRealm) {
        if (notificationState.getMode() != NotificationState.NotificationMode.enabled
                && notificationState.getMode() != NotificationState.NotificationMode.disabled)
            throw new IllegalStateException("In this method mode must be enabled or disabled.");

        if (!eventsOnChatGlobal() && notificationState.getMode() == NotificationState.NotificationMode.disabled
                || eventsOnChatGlobal() && notificationState.getMode() == NotificationState.NotificationMode.enabled)
            notificationState.setMode(NotificationState.NotificationMode.bydefault);

        setNotificationState(notificationState, needSaveToRealm);
    }

    private boolean eventsOnChatGlobal() {
        if (MUCManager.getInstance().hasRoom(account, user.getJid().asEntityBareJidIfPossible()))
            return SettingsManager.eventsOnMuc();
        else return SettingsManager.eventsOnChat();
    }

    public int getLastPosition() {
        return lastPosition;
    }

    public void saveLastPosition(int lastPosition) {
        this.lastPosition = lastPosition;
        ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public void setLastPosition(int lastPosition) {
        this.lastPosition = lastPosition;
    }

    public RealmList<ForwardId> parseForwardedMessage(boolean ui, Stanza packet, String parentMessageId) {
        List<Forwarded> forwarded = ReferencesManager.getForwardedFromReferences(packet);
        if (forwarded.isEmpty()) forwarded = ForwardManager.getForwardedFromStanza(packet);
        if (forwarded.isEmpty()) return null;

        RealmList<ForwardId> forwardedIds = new RealmList<>();
        for (Forwarded forward : forwarded) {
            Stanza stanza = forward.getForwardedStanza();
            DelayInformation delayInformation = forward.getDelayInformation();
            Date timestamp = delayInformation.getStamp();
            if (stanza instanceof Message) {
                forwardedIds.add(new ForwardId(parseInnerMessage(ui, (Message) stanza, timestamp, parentMessageId)));
            }
        }
        return forwardedIds;
    }

    protected abstract String parseInnerMessage(boolean ui, Message message, Date timestamp, String parentMessageId);

    public String getLastMessageId() {
        return lastMessageId;
    }

    public void setLastMessageId(String lastMessageId) {
        this.lastMessageId = lastMessageId;
    }

    public boolean historyIsFull() {
        return historyIsFull;
    }

    public void setHistoryIsFull() {
        this.historyIsFull = true;
    }

    public boolean isHistoryRequestedAtStart() {
        return historyRequestedAtStart;
    }

    public void setHistoryRequestedAtStart(boolean needSaveToRealm) {
        this.historyRequestedAtStart = true;
        if (needSaveToRealm) ChatManager.getInstance().saveOrUpdateChatDataToRealm(this);
    }

    public static String getStanzaId(Message message) {
        String stanzaId = null;

        stanzaId = UniqStanzaHelper.getOriginId(message);
        if (stanzaId != null && !stanzaId.isEmpty()) return stanzaId;

        stanzaId = UniqStanzaHelper.getStanzaId(message);
        if (stanzaId != null && !stanzaId.isEmpty()) return stanzaId;

        stanzaId = message.getStanzaId();
        return stanzaId;
    }

    public static Date getDelayStamp(Message message) {
        DelayInformation delayInformation = DelayInformation.from(message);
        if (delayInformation != null) {
            return delayInformation.getStamp();
        } else {
            return null;
        }
    }

    public boolean isGroupchat() {
        return isGroupchat;
    }
}