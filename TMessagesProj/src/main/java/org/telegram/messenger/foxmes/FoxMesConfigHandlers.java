package org.telegram.messenger.foxmes;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.foxmes.FoxMesModels.Chat;
import org.telegram.messenger.foxmes.FoxMesModels.NotificationDefaults;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.ArrayList;

import static org.telegram.messenger.foxmes.FoxMesHandlers.boolTrue;
import static org.telegram.messenger.foxmes.FoxMesHandlers.now;

final class FoxMesConfigHandlers {

    private FoxMesConfigHandlers() {
    }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TLRPC.TL_updates_getState.class, FoxMesTransport.Lane.INLINE, r -> r.replyLate(runtime.updates::state));
        transport.on(TLRPC.TL_updates_getDifference.class, FoxMesTransport.Lane.INLINE, r -> {
            int pts = ((TLRPC.TL_updates_getDifference) r.request).pts;
            r.replyLate(() -> runtime.updates.difference(pts));
        });
        transport.on(TLRPC.TL_help_getAppConfig.class, FoxMesTransport.Lane.INLINE, r -> r.reply(appConfig()));

        transport.on(TL_account.getNotifySettings.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.getNotifySettings req = (TL_account.getNotifySettings) r.request;
            if (req.peer instanceof TLRPC.TL_inputNotifyUsers) {
                NotificationDefaults defaults = runtime.api.notificationDefaults();
                r.reply(defaultsSettings(defaults));
                return;
            }
            TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
            r.reply(settings);
        });
        transport.on(TL_account.getReactionsNotifySettings.class, FoxMesTransport.Lane.INLINE, r -> {
            TL_account.TL_reactionsNotifySettings settings = new TL_account.TL_reactionsNotifySettings();
            settings.sound = new TLRPC.TL_notificationSoundDefault();
            settings.show_previews = true;
            r.reply(settings);
        });
        transport.on(TL_account.updateStatus.class, FoxMesTransport.Lane.INLINE, r -> r.reply(boolTrue()));
        transport.on(TL_account.registerDevice.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.registerDevice req = (TL_account.registerDevice) r.request;
            runtime.api.registerPushDevice(req.token);
            r.reply(boolTrue());
        });
        transport.on(TL_account.unregisterDevice.class, FoxMesTransport.Lane.IO, r -> {
            runtime.api.deletePushDevice();
            r.reply(boolTrue());
        });
        transport.on(TLRPC.TL_help_getTermsOfServiceUpdate.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_help_termsOfServiceUpdateEmpty empty = new TLRPC.TL_help_termsOfServiceUpdateEmpty();
            empty.expires = now() + 86400;
            r.reply(empty);
        });
        transport.on(TLRPC.TL_help_getPromoData.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_help_promoDataEmpty empty = new TLRPC.TL_help_promoDataEmpty();
            empty.expires = now() + 86400;
            r.reply(empty);
        });
        transport.on(TLRPC.TL_contacts_getContacts.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_contacts_contacts contacts = new TLRPC.TL_contacts_contacts();
            FoxMesRuntime.Snapshot snapshot = runtime.snapshot(false);
            ArrayList<Long> ids = new ArrayList<>();
            long self = runtime.selfId();
            for (Chat chat : snapshot.chats) {
                long peer = runtime.peerOf(chat);
                if (peer != 0 && peer != self && !ids.contains(peer)) {
                    ids.add(peer);
                    TLRPC.TL_contact contact = new TLRPC.TL_contact();
                    contact.user_id = peer;
                    contact.mutual = true;
                    contacts.contacts.add(contact);
                }
            }
            contacts.saved_count = 0;
            contacts.users.addAll(runtime.tlUsers(ids));
            r.reply(contacts);
        });
        transport.on(TLRPC.TL_contacts_getStatuses.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new Vector<>(TLRPC.TL_contactStatus::TLdeserialize)));
        transport.on(TLRPC.TL_messages_getDialogFilters.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new TLRPC.TL_messages_dialogFilters()));
        transport.on(TLRPC.TL_messages_getSuggestedDialogFilters.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new Vector<>(TLRPC.TL_dialogFilterSuggested::TLdeserialize)));
        transport.on(TLRPC.TL_messages_getDialogUnreadMarks.class, FoxMesTransport.Lane.INLINE, r -> r.reply(new Vector<>(TLRPC.DialogPeer::TLdeserialize)));
        transport.on(TLRPC.TL_messages_getDefaultHistoryTTL.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_defaultHistoryTTL ttl = new TLRPC.TL_defaultHistoryTTL();
            ttl.period = 0;
            r.reply(ttl);
        });
        transport.on(TLRPC.TL_messages_getAttachMenuBots.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_attachMenuBots bots = new TLRPC.TL_attachMenuBots();
            bots.hash = 1;
            r.reply(bots);
        });
        transport.on(TLRPC.TL_messages_getAvailableEffects.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_messages_availableEffects effects = new TLRPC.TL_messages_availableEffects();
            effects.hash = 1;
            r.reply(effects);
        });
        transport.on(TL_stories.TL_stories_getAllStories.class, FoxMesTransport.Lane.INLINE, r -> {
            TL_stories.TL_stories_allStories stories = new TL_stories.TL_stories_allStories();
            stories.has_more = false;
            stories.count = 0;
            stories.state = "";
            stories.stealth_mode = new TL_stories.TL_storiesStealthMode();
            r.reply(stories);
        });
        transport.on(TLRPC.TL_messages_getPeerSettings.class, FoxMesTransport.Lane.INLINE, r -> {
            TLRPC.TL_messages_peerSettings settings = new TLRPC.TL_messages_peerSettings();
            settings.settings = new TLRPC.TL_peerSettings();
            r.reply(settings);
        });
        transport.on(TLRPC.TL_auth_logOut.class, FoxMesTransport.Lane.INLINE, r -> {
            runtime.logout();
            r.reply(new TLRPC.TL_auth_loggedOut());
        });
    }

    static TLRPC.TL_peerNotifySettings defaultsSettings(NotificationDefaults defaults) {
        TLRPC.TL_peerNotifySettings settings = new TLRPC.TL_peerNotifySettings();
        settings.show_previews = true;
        settings.flags |= TLObject.FLAG_0;
        long now = System.currentTimeMillis() / 1000;
        settings.mute_until = defaults.muteUntil > now ? (int) Math.min(Integer.MAX_VALUE, defaults.muteUntil) : 0;
        settings.flags |= TLObject.FLAG_2;
        if (defaults.soundNone) {
            settings.android_sound = new TLRPC.TL_notificationSoundNone();
            settings.flags |= TLObject.FLAG_4;
        }
        return settings;
    }

    static TLRPC.TL_help_appConfig appConfig() {
        TLRPC.TL_jsonObject config = new TLRPC.TL_jsonObject();
        put(config, "stories_posting", string("disabled"));
        put(config, "premium_purchase_blocked", bool(true));
        put(config, "dialog_filters_enabled", bool(false));
        put(config, "stars_purchase_blocked", bool(true));
        put(config, "stargifts_blocked", bool(true));
        put(config, "stars_gifts_enabled", bool(false));
        boolean pushServices = ApplicationLoader.getPushProvider().hasServices();
        put(config, "keep_alive_service", bool(!pushServices));
        put(config, "background_connection", bool(!pushServices));
        put(config, "call_requests_disabled", bool(false));
        put(config, "autoarchive_setting_available", bool(false));
        put(config, "translations_manual_enabled", string("disabled"));
        put(config, "translations_auto_enabled", string("disabled"));
        put(config, "gif_search_emojies", new TLRPC.TL_jsonArray());
        TLRPC.TL_help_appConfig result = new TLRPC.TL_help_appConfig();
        result.hash = 0x0F0E5E77;
        result.config = config;
        return result;
    }

    private static void put(TLRPC.TL_jsonObject object, String key, TLRPC.JSONValue value) {
        TLRPC.TL_jsonObjectValue entry = new TLRPC.TL_jsonObjectValue();
        entry.key = key;
        entry.value = value;
        object.value.add(entry);
    }

    private static TLRPC.TL_jsonString string(String value) {
        TLRPC.TL_jsonString result = new TLRPC.TL_jsonString();
        result.value = value;
        return result;
    }

    private static TLRPC.TL_jsonBool bool(boolean value) {
        TLRPC.TL_jsonBool result = new TLRPC.TL_jsonBool();
        result.value = value;
        return result;
    }
}
