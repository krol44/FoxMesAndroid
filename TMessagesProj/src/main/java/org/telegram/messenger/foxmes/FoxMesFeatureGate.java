package org.telegram.messenger.foxmes;

public final class FoxMesFeatureGate {

    public static final boolean enabled = !FoxMesConfiguration.debugDisabled();

    private static volatile boolean ephemeralMediaSupported;

    public static final boolean directChats = true;
    public static final boolean savedMessages = true;
    public static final boolean groups = false;
    public static final boolean channels = false;
    public static final boolean calls = true;
    public static final boolean stories = false;
    public static final boolean secretChats = false;
    public static final boolean bots = false;
    public static final boolean telegramPremium = false;
    public static final boolean telegramBusiness = false;
    public static final boolean stickerCreation = false;
    public static final boolean gifSearch = false;
    public static final boolean stickerStore = false;
    public static final boolean localMessageDeletion = false;
    public static final boolean nativeSizeLargeEmoji = true;
    public static final boolean contacts = false;
    public static final boolean chatFolders = false;
    public static final boolean premiumBadge = false;
    public static final boolean qrCodes = false;
    public static final boolean profileEditing = false;
    public static final boolean nativeBlocking = false;
    public static final boolean peerMoreMenu = false;
    public static final boolean publicPosts = false;
    public static final boolean richTextArticles = false;
    public static final boolean checklists = false;
    public static final boolean polls = false;
    public static final boolean conferenceCalls = true;
    public static final boolean conferenceCallLinks = false;
    public static final boolean privacySettings = false;
    public static final boolean devices = false;
    public static final boolean telegramFeatures = false;
    public static final boolean proxy = false;
    public static final boolean notificationSounds = false;
    public static final boolean clearCloudDrafts = false;
    public static final boolean alternateAppIcons = false;
    public static final boolean settingsSearch = false;
    public static final boolean builtinReactions = false;
    public static final boolean logoutAlternatives = false;

    private FoxMesFeatureGate() {
    }

    public static boolean ephemeralMedia() {
        return ephemeralMediaSupported;
    }

    static void setEphemeralMedia(boolean value) {
        ephemeralMediaSupported = value;
    }

    public static boolean hidesContacts() {
        return enabled && !contacts;
    }

    public static boolean hidesChatFolders() {
        return enabled && !chatFolders;
    }

    public static boolean hidesPremiumBadge() {
        return enabled && !premiumBadge;
    }

    public static boolean hidesQrCodes() {
        return enabled && !qrCodes;
    }

    public static boolean hidesProfileEditing() {
        return enabled && !profileEditing;
    }

    public static boolean hidesNativeBlocking() {
        return enabled && !nativeBlocking;
    }

    public static boolean hidesPeerMoreMenu() {
        return enabled && !peerMoreMenu;
    }

    public static boolean hidesCalls() {
        return enabled && !calls;
    }

    public static boolean hidesConferenceCalls() {
        return enabled && !conferenceCalls;
    }

    public static boolean hidesConferenceCallLinks() {
        return enabled && !conferenceCallLinks;
    }

    public static boolean hidesGroups() {
        return enabled && !groups;
    }

    public static boolean hidesChannels() {
        return enabled && !channels;
    }

    public static boolean hidesBots() {
        return enabled && !bots;
    }

    public static boolean hidesPublicPosts() {
        return enabled && !publicPosts;
    }

    public static boolean hidesRichTextArticles() {
        return enabled && !richTextArticles;
    }

    public static boolean hidesPolls() {
        return enabled && !polls;
    }

    public static boolean hidesChecklists() {
        return enabled && !checklists;
    }

    public static boolean hidesPrivacySettings() {
        return enabled && !privacySettings;
    }

    public static boolean hidesDevices() {
        return enabled && !devices;
    }

    public static boolean hidesTelegramPremium() {
        return enabled && !telegramPremium && !telegramBusiness;
    }

    public static boolean hidesTelegramFeatures() {
        return enabled && !telegramFeatures;
    }

    public static boolean hidesStories() {
        return enabled && !stories;
    }

    public static boolean hidesProxy() {
        return enabled && !proxy;
    }

    public static boolean hidesNotificationSounds() {
        return enabled && !notificationSounds;
    }

    public static boolean hidesAlternateAppIcons() {
        return enabled && !alternateAppIcons;
    }

    public static boolean hidesClearCloudDrafts() {
        return enabled && !clearCloudDrafts;
    }

    public static boolean hidesStickerCreation() {
        return enabled && !stickerCreation;
    }

    public static boolean hidesStickerStore() {
        return enabled && !stickerStore;
    }

    public static boolean hidesLocalMessageDeletion() {
        return enabled && !localMessageDeletion;
    }

    public static boolean hidesGifSearch() {
        return enabled && !gifSearch;
    }

    public static boolean hidesSecretChats() {
        return enabled && !secretChats;
    }

    public static boolean hidesSettingsSearch() {
        return enabled && !settingsSearch;
    }

    public static boolean hidesBuiltinReactions() {
        return enabled && !builtinReactions;
    }

    public static boolean hidesLogoutAlternatives() {
        return enabled && !logoutAlternatives;
    }

    public static boolean supportsChat(String type, boolean isSaved) {
        return isSaved || "saved".equals(type) || "private".equals(type) || "direct".equals(type);
    }
}
