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
import android.os.Environment;
import android.os.Looper;
import androidx.annotation.Nullable;
import android.util.Pair;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.OnLoadListener;
import com.xabber.android.data.SettingsManager;
import com.xabber.android.data.SettingsManager.ChatsShowStatusChange;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.account.StatusMode;
import com.xabber.android.data.account.listeners.OnAccountDisabledListener;
import com.xabber.android.data.account.listeners.OnAccountRemovedListener;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.StanzaSender;
import com.xabber.android.data.connection.listeners.OnDisconnectListener;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.MessageDatabaseManager;
import com.xabber.android.data.database.messagerealm.Attachment;
import com.xabber.android.data.database.messagerealm.ForwardId;
import com.xabber.android.data.database.messagerealm.MessageItem;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.BaseEntity;
import com.xabber.android.data.entity.NestedMap;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.extension.captcha.Captcha;
import com.xabber.android.data.extension.captcha.CaptchaManager;
import com.xabber.android.data.extension.carbons.CarbonManager;
import com.xabber.android.data.extension.file.FileManager;
import com.xabber.android.data.extension.httpfileupload.HttpFileUploadManager;
import com.xabber.android.data.extension.muc.MUCManager;
import com.xabber.android.data.extension.muc.RoomChat;
import com.xabber.android.data.extension.references.RefUser;
import com.xabber.android.data.extension.references.ReferencesManager;
import com.xabber.android.data.groupchat.GroupchatUserManager;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.chat.ChatManager;
import com.xabber.android.data.message.chat.MucPrivateChatNotification;
import com.xabber.android.data.notification.EntityNotificationProvider;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.data.roster.OnRosterReceivedListener;
import com.xabber.android.data.roster.OnStatusChangeListener;
import com.xabber.android.data.roster.PresenceManager;
import com.xabber.android.data.roster.RosterManager;
import com.xabber.android.utils.StringUtils;

import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smackx.carbons.packet.CarbonExtension;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jxmpp.jid.FullJid;
import org.jxmpp.jid.Jid;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmResults;

/**
 * Manage chats and its messages.
 * <p/>
 * Warning: message processing using chat instances should be changed.
 *
 * @author alexander.ivanov
 */
public class MessageManager implements OnLoadListener, OnPacketListener, OnDisconnectListener,
        OnAccountRemovedListener, OnAccountDisabledListener, OnRosterReceivedListener,
        OnStatusChangeListener {

    private static MessageManager instance;

    private final EntityNotificationProvider<MucPrivateChatNotification> mucPrivateChatRequestProvider;

    /**
     * Registered chats for bareAddresses in accounts.
     */
    private final NestedMap<AbstractChat> chats;
    /**
     * Visible chat.
     * <p/>
     * Will be <code>null</code> if there is no one.
     */
    private AbstractChat visibleChat;

    public static MessageManager getInstance() {
        if (instance == null) {
            instance = new MessageManager();
        }

        return instance;
    }

    private MessageManager() {
        chats = new NestedMap<>();

        mucPrivateChatRequestProvider = new EntityNotificationProvider<>
                (R.drawable.ic_stat_muc_private_chat_request_white_24dp);
        mucPrivateChatRequestProvider.setCanClearNotifications(false);
    }

    @Override
    public void onLoad() {
        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();

        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                RealmResults<MessageItem> messagesToSend = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.SENT, false)
                        .findAll();

                for (MessageItem messageItem : messagesToSend) {
                    AccountJid account = messageItem.getAccount();
                    UserJid user = messageItem.getUser();

                    if (account != null && user != null) {
                        if (getChat(account, user) == null) {
                            createChat(account, user);
                        }
                    }
                }
            }
        });
        realm.close();

        NotificationManager.getInstance().registerNotificationProvider(mucPrivateChatRequestProvider);
    }

    /**
     * @return <code>null</code> if there is no such chat.
     */

    @Nullable
    public AbstractChat getChat(AccountJid account, UserJid user) {
        if (account != null && user != null) {
            return chats.get(account.toString(), user.getBareJid().toString());
        } else {
            return null;
        }
    }

    public Collection<AbstractChat> getChatsOfEnabledAccount() {
        List<AbstractChat> chats = new ArrayList<>();

        HashSet<AccountJid> enabledAccounts = new HashSet<>();
        enabledAccounts.addAll(AccountManager.getInstance().getEnabledAccounts());
        enabledAccounts.addAll(AccountManager.getInstance().getCachedEnabledAccounts());

        for (AccountJid accountJid : enabledAccounts) {
            chats.addAll(this.chats.getNested(accountJid.toString()).values());
        }
        return chats;
    }

    public Collection<AbstractChat> getChats() {
        List<AbstractChat> chats = new ArrayList<>();
        for (AccountJid accountJid : AccountManager.getInstance().getAllAccounts()) {
            chats.addAll(this.chats.getNested(accountJid.toString()).values());
        }
        return chats;
    }

    public Collection<AbstractChat> getChats(AccountJid account) {
        List<AbstractChat> chats = new ArrayList<>();
        chats.addAll(this.chats.getNested(account.toString()).values());
        return chats;
    }

    /**
     * Creates and adds new regular chat to be managed.
     *
     * @param account
     * @param user
     * @return
     */
    private RegularChat createChat(AccountJid account, UserJid user) {
        RegularChat chat = new RegularChat(account, user, false);
        ChatData chatData = ChatManager.getInstance().loadChatDataFromRealm(chat);
        if (chatData != null) {
            chat.setLastPosition(chatData.getLastPosition());
            chat.setArchived(chatData.isArchived(), false);
            chat.setNotificationState(chatData.getNotificationState(), false);
            if (chatData.isHistoryRequestedAtStart()) chat.setHistoryRequestedAtStart(false);
        }
        addChat(chat);
        return chat;
    }

    private RegularChat createPrivateMucChat(AccountJid account, FullJid fullJid) throws UserJid.UserJidCreateException {
        RegularChat chat = new RegularChat(account, UserJid.from(fullJid), true);
        ChatData chatData = ChatManager.getInstance().loadChatDataFromRealm(chat);
        if (chatData != null) {
            chat.setLastPosition(chatData.getLastPosition());
            chat.setArchived(chatData.isArchived(), false);
            chat.setNotificationState(chatData.getNotificationState(), false);
            if (chatData.isHistoryRequestedAtStart()) chat.setHistoryRequestedAtStart(false);
        }
        addChat(chat);
        return chat;
    }

    /**
     * Adds chat to be managed.
     *
     * @param chat
     */
    public void addChat(AbstractChat chat) {
        if (getChat(chat.getAccount(), chat.getUser()) != null) {
            return;
        }
        chats.put(chat.getAccount().toString(), chat.getUser().toString(), chat);
    }

    /**
     * Removes chat from managed.
     *
     * @param chat
     */
    public void removeChat(AbstractChat chat) {
        chat.closeChat();
        LogManager.i(this, "removeChat " + chat.getUser());
        chats.remove(chat.getAccount().toString(), chat.getUser().toString());
    }

    /**
     * Sends message. Creates and registers new chat if necessary.
     *
     * @param account
     * @param user
     * @param text
     */
    public void sendMessage(AccountJid account, UserJid user, String text) {
        AbstractChat chat = getOrCreateChat(account, user);
        sendMessage(text, chat);

        // stop grace period
        AccountManager.getInstance().stopGracePeriod(account);
    }

    private void sendMessage(final String text, final AbstractChat chat) {
        MessageDatabaseManager.getInstance().getRealmUiThread()
                .executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem newMessageItem = chat.createNewMessageItem(text);
                realm.copyToRealm(newMessageItem);
                if (chat.canSendMessage())
                    chat.sendMessages();
            }
        });

        // mark incoming messages as read
        chat.markAsReadAll(true);
    }

    public String createFileMessage(AccountJid account, UserJid user, List<File> files) {
        AbstractChat chat = getOrCreateChat(account, user);
        chat.openChat();
        return chat.newFileMessage(files, null);
    }

    public String createFileMessageFromUris(AccountJid account, UserJid user, List<Uri> uris) {
        AbstractChat chat = getOrCreateChat(account, user);
        chat.openChat();
        return chat.newFileMessage(null, uris);
    }

    public void updateFileMessage(AccountJid account, UserJid user, final String messageId,
                                  final HashMap<String, String> urls, final List<String> notUploadedFilesUrls) {
        final AbstractChat chat = getChat(account, user);
        if (chat == null) {
            return;
        }

        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();

        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem messageItem = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                        .findFirst();

                if (messageItem != null) {
                    RealmList<Attachment> attachments = messageItem.getAttachments();

                    // remove attachments that not uploaded
                    for (String file : notUploadedFilesUrls) {
                        for (Attachment attachment : attachments) {
                            if (file.equals(attachment.getFilePath())) {
                                attachments.remove(attachment);
                                break;
                            }
                        }
                    }

                    for (Attachment attachment : attachments) {
                        attachment.setFileUrl(urls.get(attachment.getFilePath()));
                    }

                    messageItem.setText("");
                    messageItem.setSent(false);
                    messageItem.setInProgress(false);
                    messageItem.setError(false);
                    messageItem.setErrorDescription("");
                }
            }
        });

        realm.close();
        chat.sendMessages();
    }

    public void updateMessageWithNewAttachments(final String messageId, final List<File> files) {
        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem messageItem = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                        .findFirst();

                if (messageItem != null) {
                    RealmList<Attachment> attachments = messageItem.getAttachments();

                    // remove temporary attachments created from uri
                    // to replace it with attachments created from files
                    attachments.deleteAllFromRealm();

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
                }
            }
        });
    }

    public void updateMessageWithError(final String messageId, final String errorDescription) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Realm realm = MessageDatabaseManager.getInstance().getRealmUiThread();
            realm.executeTransactionAsync(new Realm.Transaction() {
                @Override
                public void execute(Realm realm) {
                    updateMessageWithError(realm, messageId, errorDescription);
                }
            });
        } else {
            Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
            realm.executeTransaction(new Realm.Transaction() {
                @Override
                public void execute(Realm realm) {
                    updateMessageWithError(realm, messageId, errorDescription);
                }
            });
        }
    }

    private void updateMessageWithError(Realm realm, final String messageId, final String errorDescription) {
        MessageItem messageItem = realm.where(MessageItem.class)
                .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                .findFirst();

        if (messageItem != null) {
            messageItem.setError(true);
            messageItem.setErrorDescription(errorDescription);
            messageItem.setInProgress(false);
        }
    }

    public void removeErrorAndResendMessage(AccountJid account, UserJid user, final String messageId) {
        final AbstractChat chat = getChat(account, user);
        if (chat == null) {
            return;
        }

        Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
        realm.executeTransaction(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MessageItem messageItem = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.UNIQUE_ID, messageId)
                        .findFirst();

                if (messageItem != null) {
                    messageItem.setError(false);
                    messageItem.setSent(false);
                    messageItem.setErrorDescription("");
                }
            }
        });

        realm.close();
        chat.sendMessages();
    }

    /**
     * @param account
     * @param user
     * @return Where there is active chat.
     */
    public boolean hasActiveChat(AccountJid account, UserJid user) {
        AbstractChat chat = getChat(account, user);
        return chat != null && chat.isActive();
    }

    /**
     * @return Collection with active chats.
     */
    public Collection<AbstractChat> getActiveChats() {
        Collection<AbstractChat> collection = new ArrayList<>();
        for (AbstractChat chat : chats.values()) {
            if (chat.isActive()) {
                collection.add(chat);
            }
        }
        return Collections.unmodifiableCollection(collection);
    }

    public AbstractChat getOrCreateChat(AccountJid account, UserJid user, MessageItem lastMessage) {
        AbstractChat chat = getOrCreateChat(account, user);
        chat.setLastMessage(lastMessage);
        return chat;
    }

    /**
     * Returns existed chat or create new one.
     *
     */
    public AbstractChat getOrCreateChat(AccountJid account, UserJid user) {
        if (MUCManager.getInstance().isMucPrivateChat(account, user)) {
            try {
                return getOrCreatePrivateMucChat(account, user.getJid().asFullJidIfPossible());
            } catch (UserJid.UserJidCreateException e) {
                return null;
            }
        }

        AbstractChat chat = getChat(account, user);
        if (chat == null) {
            chat = createChat(account, user);
        }
        return chat;
    }

    public AbstractChat getOrCreatePrivateMucChat(AccountJid account, FullJid fullJid) throws UserJid.UserJidCreateException {
        AbstractChat chat = getChat(account, UserJid.from(fullJid));
        if (chat == null) {
            chat = createPrivateMucChat(account, fullJid);
        }
        return chat;
    }


    /**
     * Force open chat (make it active).
     *
     * @param account
     * @param user
     */
    public void openChat(AccountJid account, UserJid user) {
        getOrCreateChat(account, user).openChat();
    }

    public void openPrivateMucChat(AccountJid account, FullJid fullJid) throws UserJid.UserJidCreateException {
        getOrCreatePrivateMucChat(account, fullJid).openChat();
    }

    /**
     * Closes specified chat (make it inactive).
     *
     * @param account
     * @param user
     */
    public void closeChat(AccountJid account, UserJid user) {
        AbstractChat chat = getChat(account, user);
        if (chat == null) {
            return;
        }
        chat.closeChat();
    }

    /**
     * Sets currently visible chat.
     */
    public void setVisibleChat(BaseEntity visibleChat) {
        AbstractChat chat = getChat(visibleChat.getAccount(), visibleChat.getUser());
        if (chat == null)
            chat = createChat(visibleChat.getAccount(), visibleChat.getUser());
        this.visibleChat = chat;
    }

    /**
     * All chats become invisible.
     */
    public void removeVisibleChat() {
        visibleChat = null;
    }

    /**
     * @param chat
     * @return Whether specified chat is currently visible.
     */
    public boolean isVisibleChat(AbstractChat chat) {
        return visibleChat == chat;
    }

    /**
     * Removes all messages from chat.
     *
     * @param account
     * @param user
     */
    public void clearHistory(final AccountJid account, final UserJid user) {
        final long startTime = System.currentTimeMillis();

        MessageDatabaseManager.getInstance().getRealmUiThread()
                .executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.ACCOUNT, account.toString())
                        .equalTo(MessageItem.Fields.USER, user.toString())
                        .findAll().deleteAllFromRealm();
                LogManager.d("REALM", Thread.currentThread().getName()
                        + " clear history: " + (System.currentTimeMillis() - startTime));
            }
        });
    }

    /**
     * Removes message from history.
     *
     */
    public void removeMessage(final String messageItemId) {
        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();

                MessageItem messageItem = realm.where(MessageItem.class)
                        .equalTo(MessageItem.Fields.UNIQUE_ID, messageItemId).findFirst();
                if (messageItem != null) {
                    realm.beginTransaction();
                    messageItem.deleteFromRealm();
                    realm.commitTransaction();
                }

                realm.close();
            }
        });
    }

    /**
     * Removes message from history.
     *
     */
    public void removeMessage(final List<String> messageIDs) {
        final String[] ids = messageIDs.toArray(new String[0]);
        Application.getInstance().runInBackgroundUserRequest(new Runnable() {
            @Override
            public void run() {
                Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
                RealmResults<MessageItem> items = realm.where(MessageItem.class)
                        .in(MessageItem.Fields.UNIQUE_ID, ids).findAll();

                if (items != null && !items.isEmpty()) {
                    realm.beginTransaction();
                    items.deleteAllFromRealm();
                    realm.commitTransaction();
                }
                realm.close();
            }
        });
    }


    /**
     * Called on action settings change.
     */
    public void onSettingsChanged() {

    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza stanza) {
        if (stanza.getFrom() == null) {
            return;
        }
        AccountJid account = connection.getAccount();

        final UserJid user;
        try {
            user = UserJid.from(stanza.getFrom()).getBareUserJid();
        } catch (UserJid.UserJidCreateException e) {
            return;
        }
        boolean processed = false;
        List<AbstractChat> chatsCopy = new ArrayList<>();
        chatsCopy.addAll(chats.getNested(account.toString()).values());
        for (AbstractChat chat : chatsCopy) {
            if (chat.onPacket(user, stanza, false)) {
                processed = true;
                break;
            }
        }

        final AbstractChat chat = getChat(account, user);

        if (chat != null && stanza instanceof Message) {
            if (chat.isPrivateMucChat() && !chat.isPrivateMucChatAccepted()) {
                if (mucPrivateChatRequestProvider.get(chat.getAccount(), chat.getUser()) == null) {
                    mucPrivateChatRequestProvider.add(new MucPrivateChatNotification(account, user), true);
                }
            }


            return;
        }
        if (!processed && stanza instanceof Message) {
            final Message message = (Message) stanza;
            final String body = message.getBody();
            if (body == null) {
                return;
            }

            //check for spam
            if (SettingsManager.spamFilterMode() != SettingsManager.SpamFilterMode.disabled
                    && RosterManager.getInstance().getRosterContact(account, user) == null ) {

                String thread = ((Message) stanza).getThread();

                if (SettingsManager.spamFilterMode() == SettingsManager.SpamFilterMode.authCaptcha) {
                    // check if this message is captcha-answer
                    Captcha captcha = CaptchaManager.getInstance().getCaptcha(account, user);
                    if (captcha != null) {
                        // attempt limit overhead
                        if (captcha.getAttemptCount() > CaptchaManager.CAPTCHA_MAX_ATTEMPT_COUNT) {
                            // remove this captcha
                            CaptchaManager.getInstance().removeCaptcha(account, user);
                            // discard subscription
                            try {
                                PresenceManager.getInstance().discardSubscription(account, user);
                            } catch (NetworkException e) {
                                e.printStackTrace();
                            }
                            sendMessageWithoutChat(user.getJid(), thread, account,
                                    Application.getInstance().getResources().getString(R.string.spam_filter_captcha_many_attempts));
                            return;
                        }
                        if (body.equals(captcha.getAnswer())) {
                            // captcha solved successfully
                            // remove this captcha
                            CaptchaManager.getInstance().removeCaptcha(account, user);

                            // show auth
                            PresenceManager.getInstance().handleSubscriptionRequest(account, user);
                            sendMessageWithoutChat(user.getJid(), thread, account,
                                    Application.getInstance().getResources().getString(R.string.spam_filter_captcha_correct));
                            return;
                        } else {
                            // captcha solved unsuccessfully
                            // increment attempt count
                            captcha.setAttemptCount(captcha.getAttemptCount() + 1);
                            // send warning-message
                            sendMessageWithoutChat(user.getJid(), thread, account,
                                    Application.getInstance().getResources().getString(R.string.spam_filter_captcha_incorrect));
                            return;
                        }
                    } else {
                        // no captcha exist and user not from roster
                        sendMessageWithoutChat(user.getJid(), thread, account,
                                Application.getInstance().getResources().getString(R.string.spam_filter_limit_message));
                        // and skip received message as spam
                        return;
                    }

                } else {
                    // if message from not-roster user
                    // send a warning message to sender
                    sendMessageWithoutChat(user.getJid(), thread, account,
                            Application.getInstance().getResources().getString(R.string.spam_filter_limit_message));
                    // and skip received message as spam
                    return;
                }
            }

            if (message.getType() == Message.Type.chat && MUCManager.getInstance().hasRoom(account, user.getJid().asEntityBareJidIfPossible())) {
                try {
                    createPrivateMucChat(account, user.getJid().asFullJidIfPossible()).onPacket(user, stanza, false);
                } catch (UserJid.UserJidCreateException e) {
                    LogManager.exception(this, e);
                }
                mucPrivateChatRequestProvider.add(new MucPrivateChatNotification(account, user), true);
                return;
            }

            for (ExtensionElement packetExtension : message.getExtensions()) {
                if (packetExtension instanceof MUCUser) {
                    return;
                }
            }

            createChat(account, user).onPacket(user, stanza, false);
        }
    }

    // send messages without creating chat and adding to roster
    // used for service auto-generated messages
    public void sendMessageWithoutChat(Jid to, String threadId, AccountJid account, String text) {
        Message message = new Message();
        message.setTo(to);
        message.setType(Message.Type.chat);
        message.setBody(text);
        message.setThread(threadId);
        // send auto-generated messages without carbons
        CarbonManager.getInstance().setMessageToIgnoreCarbons(message);
        try {
            StanzaSender.sendStanza(account, message);
        } catch (NetworkException e) {
            e.printStackTrace();
        }
    }

    public void processCarbonsMessage(AccountJid account, final Message message, CarbonExtension.Direction direction) {
        if (direction == CarbonExtension.Direction.sent) {
            UserJid companion;
            try {
                companion = UserJid.from(message.getTo()).getBareUserJid();
            } catch (UserJid.UserJidCreateException e) {
                return;
            }
            AbstractChat chat = getChat(account, companion);
            if (chat == null) {
                chat = createChat(account, companion);
            }
            final String body = message.getBody();
            if (body == null) {
                return;
            }

            final AbstractChat finalChat = chat;

            String text = body;
            String uid = UUID.randomUUID().toString();
            RealmList<ForwardId> forwardIds = finalChat.parseForwardedMessage(true, message, uid);
            String originalStanza = message.toXML().toString();
            String originalFrom = message.getFrom().toString();

            // forward comment (to support previous forwarded xep)
            String forwardComment = ForwardManager.parseForwardComment(message);
            if (forwardComment != null) text = forwardComment;

            // modify body with references
            Pair<String, String> bodies = ReferencesManager.modifyBodyWithReferences(message, text);
            text = bodies.first;
            String markupText = bodies.second;

            MessageItem newMessageItem = finalChat.createNewMessageItem(text);
            newMessageItem.setStanzaId(AbstractChat.getStanzaId(message));
            newMessageItem.setSent(true);
            newMessageItem.setForwarded(true);
            if (markupText != null) newMessageItem.setMarkupText(markupText);

            // forwarding
            if (forwardIds != null) newMessageItem.setForwardedIds(forwardIds);
            newMessageItem.setOriginalStanza(originalStanza);
            newMessageItem.setOriginalFrom(originalFrom);

            // attachments
            RealmList<Attachment> attachments = HttpFileUploadManager.parseFileMessage(message);
            if (attachments.size() > 0)
                newMessageItem.setAttachments(attachments);

            // groupchat
            RefUser groupchatUser = ReferencesManager.getGroupchatUserFromReferences(message);
            if (groupchatUser != null) {
                GroupchatUserManager.getInstance().saveGroupchatUser(groupchatUser);
                newMessageItem.setGroupchatUserId(groupchatUser.getId());
            }

            BackpressureMessageSaver.getInstance().saveMessageItem(newMessageItem);

            // mark incoming messages as read
            finalChat.markAsReadAll(false);

            // start grace period
            AccountManager.getInstance().startGracePeriod(account);
            return;
        }

        UserJid companion = null;
        try {
            companion = UserJid.from(message.getFrom()).getBareUserJid();
        } catch (UserJid.UserJidCreateException e) {
            return;
        }

        //check for spam
        if (SettingsManager.spamFilterMode() != SettingsManager.SpamFilterMode.disabled
                && RosterManager.getInstance().getRosterContact(account, companion) == null ) {
            // just ignore carbons from not-authorized user
            return;
        }

        boolean processed = false;
        for (AbstractChat chat : chats.getNested(account.toString()).values()) {
            if (chat.onPacket(companion, message, true)) {
                processed = true;
                break;
            }
        }
        if (getChat(account, companion) != null) {
            return;
        }
        if (processed) {
            return;
        }
        final String body = message.getBody();
        if (body == null) {
            return;
        }
        createChat(account, companion).onPacket(companion, message, true);

    }
    @Override
    public void onRosterReceived(AccountItem accountItem) {
        for (AbstractChat chat : chats.getNested(accountItem.getAccount().toString()).values()) {
            chat.onComplete();
        }
    }

    @Override
    public void onDisconnect(ConnectionItem connection) {
        if (!(connection instanceof AccountItem)) {
            return;
        }
        AccountJid account = connection.getAccount();
        for (AbstractChat chat : chats.getNested(account.toString()).values()) {
            chat.onDisconnect();
        }
    }

    @Override
    public void onAccountRemoved(AccountItem accountItem) {
        chats.clear(accountItem.getAccount().toString());
    }

    @Override
    public void onAccountDisabled(AccountItem accountItem) {
        chats.clear(accountItem.getAccount().toString());
    }

    /**
     * Export chat to file with specified name.
     *
     * @param account
     * @param user
     * @param fileName
     * @throws NetworkException
     */
    public File exportChat(AccountJid account, UserJid user, String fileName) throws NetworkException {
        final File file = new File(Environment.getExternalStorageDirectory(), fileName);
        try {
            BufferedWriter out = new BufferedWriter(new FileWriter(file));
            final String titleName = RosterManager.getInstance().getName(account, user) + " (" + user + ")";
            out.write("<html><head><title>");
            out.write(StringUtils.escapeHtml(titleName));
            out.write("</title></head><body>");
            final AbstractChat abstractChat = getChat(account, user);
            if (abstractChat != null) {
                final boolean isMUC = abstractChat instanceof RoomChat;
                final String accountName = AccountManager.getInstance().getNickName(account);
                final String userName = RosterManager.getInstance().getName(account, user);

                Realm realm = MessageDatabaseManager.getInstance().getNewBackgroundRealm();
                RealmResults<MessageItem> messageItems = MessageDatabaseManager.getChatMessages(realm, account, user);

                for (MessageItem messageItem : messageItems) {
                    if (messageItem.getAction() != null) {
                        continue;
                    }
                    final String name;
                    if (isMUC) {
                        name = messageItem.getResource().toString();
                    } else {
                        if (messageItem.isIncoming()) {
                            name = userName;
                        } else {
                            name = accountName;
                        }
                    }
                    out.write("<b>");
                    out.write(StringUtils.escapeHtml(name));
                    out.write("</b>&nbsp;(");
                    out.write(StringUtils.getDateTimeText(new Date(messageItem.getTimestamp())));
                    out.write(")<br />\n<p>");
                    out.write(StringUtils.escapeHtml(messageItem.getText()));
                    out.write("</p><hr />\n");
                }
                realm.close();
            }
            out.write("</body></html>");
            out.close();
        } catch (IOException e) {
            throw new NetworkException(R.string.FILE_NOT_FOUND);
        }
        return file;
    }

    private boolean isStatusTrackingEnabled(AccountJid account, UserJid user) {
        if (SettingsManager.chatsShowStatusChange() != ChatsShowStatusChange.always) {
            return false;
        }
        AbstractChat abstractChat = getChat(account, user);
        return abstractChat != null && abstractChat instanceof RegularChat && (isVisibleChat(abstractChat) || abstractChat.isActive());
    }

    @Override
    public void onStatusChanged(AccountJid account, final UserJid user, final String statusText) {
        // temporary disabled
//        if (isStatusTrackingEnabled(account, user)) {
//            final AbstractChat chat = getChat(account, user);
//            if (chat != null) {
//                Application.getInstance().runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        // fix for saving to realm
//                        String text;
//                        if (statusText != null) {
//                            if (!statusText.isEmpty() && statusText.length() > 0) text = statusText;
//                            else text = " ";
//                        } else text = " ";
//                        // create new action
//                        chat.newAction(user.getJid().getResourceOrNull(), text, ChatAction.status);
//                    }
//                });
//            }
//        }
    }

    @Override
    public void onStatusChanged(AccountJid account, final UserJid user, final StatusMode statusMode, final String statusText) {
        // temporary disabled
//        if (isStatusTrackingEnabled(account, user)) {
//            final AbstractChat chat = getChat(account, user);
//            if (chat != null) {
//                Application.getInstance().runOnUiThread(new Runnable() {
//                    @Override
//                    public void run() {
//                        // fix for saving to realm
//                        String text;
//                        if (statusText != null) {
//                            if (!statusText.isEmpty() && statusText.length() > 0) text = statusText;
//                            else text = " ";
//                        } else text = " ";
//                        // create new action
//                        chat.newAction(user.getJid().getResourceOrNull(),
//                                text, ChatAction.getChatAction(statusMode));
//                    }
//                });
//            }
//        }
    }

    public void acceptMucPrivateChat(AccountJid account, UserJid user) throws UserJid.UserJidCreateException {
        mucPrivateChatRequestProvider.remove(account, user);
        getOrCreatePrivateMucChat(account, user.getJid().asFullJidIfPossible()).setIsPrivateMucChatAccepted(true);
    }

    public void discardMucPrivateChat(AccountJid account, UserJid user) {
        mucPrivateChatRequestProvider.remove(account, user);
    }

    public static void closeActiveChats() {
        for (AbstractChat chat : MessageManager.getInstance().getActiveChats()) {
            MessageManager.getInstance().closeChat(chat.getAccount(), chat.getUser());
            NotificationManager.getInstance().
                    removeMessageNotification(chat.getAccount(), chat.getUser());
        }
    }

    public static void setAttachmentLocalPathToNull(final String uniqId) {
        final Realm realm = MessageDatabaseManager.getInstance().getRealmUiThread();
        realm.executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                Attachment first = realm.where(Attachment.class)
                        .equalTo(Attachment.Fields.UNIQUE_ID, uniqId)
                        .findFirst();
                if (first != null) {
                    first.setFilePath(null);
                }
            }
        });
    }
}