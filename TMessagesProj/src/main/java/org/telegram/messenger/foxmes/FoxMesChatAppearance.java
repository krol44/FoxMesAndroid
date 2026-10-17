package org.telegram.messenger.foxmes;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.util.zip.GZIPInputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import android.graphics.Bitmap;
import android.widget.Toast;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.theme.ThemeKey;
import okhttp3.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatThemeController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.ResultCallback;
import org.telegram.ui.ActionBar.EmojiThemes;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.messenger.foxmes.FoxMesModels.*;

public final class FoxMesChatAppearance {
    public static class Wallpaper {
        public String sha256, url, kind, pattern_id, mime;
        public long size;
        public int width, height, rotation, intensity;
        public boolean motion;
        public List<Integer> colors;
    }
    public static class InitialAppearance { public String theme; public boolean wallpaper_none; }
    public static class WallpaperState {
        public InitialAppearance appearance;
        public Wallpaper wallpaper;
        public boolean blurred, none;
        public int intensity;
        public long settings_revision;
    }
    public static class ThemeState { public String theme_emoticon; public long revision; }
    public static class Appearance { public ThemeState theme; public WallpaperState wallpaper; }
    public static class AppearanceResult extends Appearance { public Message message; }
    public static class Action { public String type, theme_emoticon; public WallpaperState wallpaper; public boolean for_both; public int message_id; }
    public static class Settings {
        public String base_theme;
        public int accent_color;
        public Integer outgoing_accent_color;
        public List<Integer> message_colors;
        public boolean animate_message_colors;
        public Wallpaper wallpaper;
    }
    public static class ThemeDefinition { public long id; public String emoticon, title; public List<String> aliases; public List<Settings> settings; }
    public static class Catalog { public long hash; public List<ThemeDefinition> items; }
    public static class Gallery { public List<Wallpaper> items; }
    private static class Themes { List<ThemeDefinition> items; }
    private static final Map<String, Appearance> states = new ConcurrentHashMap<>();
    private static final Map<String, Wallpaper> papers = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> themeAliases = new ConcurrentHashMap<>();
    private static String scope(FoxMesRuntime runtime) { return FoxMesConfiguration.baseURL() + ":" + runtime.account + ":" + runtime.selfId() + ":"; }

    static void register(FoxMesTransport transport, FoxMesRuntime runtime) {
        transport.on(TL_account.getThemes.class, FoxMesTransport.Lane.IO, r -> {
            Themes definitions = runtime.api.http.request("GET", "/themes", null, null, Themes.class);
            Catalog catalog = catalog(runtime);
            TL_account.TL_themes result = new TL_account.TL_themes();
            result.hash = catalog.hash;
            for (ThemeDefinition definition : FoxMesModels.list(catalog.items)) result.themes.add(settingsTheme(runtime, definition));
            for (ThemeDefinition definition : FoxMesModels.list(definitions.items)) result.themes.add(theme(runtime, definition));
            r.reply(result);
        });
        transport.on(TL_account.getTheme.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.getTheme req = (TL_account.getTheme) r.request;
            if (req.theme instanceof TLRPC.TL_inputTheme) {
                long nativeId = ((TLRPC.TL_inputTheme) req.theme).id;
                if (nativeId >= 900000000 && nativeId < 1000000000) {
                    for (ThemeDefinition definition : FoxMesModels.list(catalog(runtime).items)) {
                        if (nativeId == 900000000 + definition.id) { r.reply(settingsTheme(runtime, definition)); return; }
                    }
                    r.fail(400, "THEME_INVALID");
                    return;
                }
            }
            long id;
            if (req.theme instanceof TLRPC.TL_inputTheme) {
                id = ((TLRPC.TL_inputTheme) req.theme).id - 1000000000;
            } else if (req.theme instanceof TLRPC.TL_inputThemeSlug) {
                String slug = ((TLRPC.TL_inputThemeSlug) req.theme).slug;
                if (slug != null && slug.startsWith("foxmes-chat-")) {
                    for (ThemeDefinition definition : FoxMesModels.list(catalog(runtime).items)) {
                        if (slug.equals("foxmes-chat-" + definition.id)) { r.reply(settingsTheme(runtime, definition)); return; }
                    }
                    r.fail(400, "THEME_INVALID");
                    return;
                }
                try {
                    if (slug == null || !slug.startsWith("foxmes-")) throw new NumberFormatException();
                    id = Long.parseLong(slug.substring(7));
                } catch (NumberFormatException invalid) {
                    r.fail(400, "THEME_INVALID");
                    return;
                }
            } else {
                r.fail(400, "THEME_INVALID");
                return;
            }
            if (id <= 0) { r.fail(400, "THEME_INVALID"); return; }
            r.reply(theme(runtime, runtime.api.http.request("GET", "/themes/" + id, null, null, ThemeDefinition.class)));
        });
        transport.on(TL_account.saveTheme.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.saveTheme req = (TL_account.saveTheme) r.request;
            if (req.unsave && req.theme instanceof TLRPC.TL_inputTheme) operation(runtime, "DELETE", "/themes/" + (((TLRPC.TL_inputTheme) req.theme).id - 1000000000), FoxMesJson.body(), JsonObject.class);
            r.reply(FoxMesHandlers.boolTrue());
        });
        transport.on(TL_account.installTheme.class, FoxMesTransport.Lane.SERIAL, r -> r.reply(FoxMesHandlers.boolTrue()));

        transport.on(TL_account.createTheme.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.createTheme req = (TL_account.createTheme) r.request;
            if (req.settings == null) { r.fail(400, "THEME_SETTINGS_REQUIRED"); return; }
            Settings settings = themeSettings(runtime, req.settings);
            FoxMesJson.Body body = FoxMesJson.body().put("title", req.title).put("settings", FoxMesJson.gson.toJsonTree(Collections.singletonList(settings)));
            r.reply(theme(runtime, operation(runtime, "POST", "/themes", body, ThemeDefinition.class)));
        });
        transport.on(TL_account.updateTheme.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.updateTheme req = (TL_account.updateTheme) r.request;
            if (!(req.theme instanceof TLRPC.TL_inputTheme)) { r.fail(400, "THEME_INVALID"); return; }
            long id = ((TLRPC.TL_inputTheme) req.theme).id - 1000000000;
            ThemeDefinition current = runtime.http.request("GET", "/themes/" + id, null, null, ThemeDefinition.class);
            List<Settings> settings = req.settings != null ? Collections.singletonList(themeSettings(runtime, req.settings)) : current.settings;
            FoxMesJson.Body body = FoxMesJson.body().put("title", (req.flags & 2) != 0 ? req.title : current.title).put("settings", FoxMesJson.gson.toJsonTree(settings));
            r.reply(theme(runtime, operation(runtime, "PUT", "/themes/" + id, body, ThemeDefinition.class)));
        });
        transport.on(TL_account.getWallPaper.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.getWallPaper req = (TL_account.getWallPaper) r.request;
            r.reply(wallpaper(runtime, choice(runtime, req.wallpaper, null)));
        });
        transport.on(TL_account.Tl_getUniqueGiftChatThemes.class, FoxMesTransport.Lane.IO, r -> {
            TL_account.Tl_chatThemes result = emptyGiftThemes();
            AndroidUtilities.runOnUIThread(() -> ChatThemeController.getInstance(runtime.account)
                .requestAllChatThemes(new ResultCallback<List<EmojiThemes>>() {
                    @Override
                    public void onComplete(List<EmojiThemes> themes) { r.reply(result); }
                    @Override
                    public void onError(TLRPC.TL_error error) { r.fail(error.code, error.text); }
                }, false));
        });
        transport.on(TL_account.getChatThemes.class, FoxMesTransport.Lane.IO, r -> {
            Catalog catalog = catalog(runtime);
            TL_account.TL_themes result = new TL_account.TL_themes(); result.hash = catalog.hash;
            for (ThemeDefinition item : FoxMesModels.list(catalog.items)) result.themes.add(theme(runtime, item));
            r.reply(result);
        });
        transport.on(TL_account.getWallPapers.class, FoxMesTransport.Lane.IO, r -> {
            Gallery gallery = runtime.api.http.request("GET", "/wallpapers", null, null, Gallery.class);
            Catalog catalog = catalog(runtime);
            TL_account.TL_wallPapers result = new TL_account.TL_wallPapers();
            for (Wallpaper paper : FoxMesModels.list(gallery.items)) result.wallpapers.add(wallpaper(runtime, state(paper, null)));
            Set<String> patterns = new HashSet<>();
            for (ThemeDefinition item : FoxMesModels.list(catalog.items)) {
                for (Settings variant : FoxMesModels.list(item.settings)) {
                    Wallpaper paper = variant.wallpaper;
                    if (paper != null && "pattern".equals(paper.kind) && paper.pattern_id != null && patterns.add(paper.pattern_id)) {
                        result.wallpapers.add(wallpaper(runtime, state(paper, null)));
                    }
                }
            }
            r.reply(result);
        });
        transport.on(TLRPC.TL_messages_setChatTheme.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_setChatTheme req = (TLRPC.TL_messages_setChatTheme) r.request;
            String emoticon = req.theme instanceof TLRPC.Tl_inputChatTheme ? ((TLRPC.Tl_inputChatTheme) req.theme).emoticon : "";
            mutate(runtime, r, req.peer, FoxMesJson.body().put("theme_emoticon", emoticon).put("reset_wallpaper", "none"));
        });
        transport.on(TLRPC.TL_messages_setChatWallPaper.class, FoxMesTransport.Lane.SERIAL, r -> {
            TLRPC.TL_messages_setChatWallPaper req = (TLRPC.TL_messages_setChatWallPaper) r.request;
            long chatId = runtime.ensureChatId(FoxMesHandlers.peerUserId(req.peer, runtime));
            WallpaperState choice;
            if (req.revert) {
                mutate(runtime, r, req.peer, FoxMesJson.body().put("revert", true)); return;
            } else if ((req.flags & 2) != 0) {
                Message message = runtime.api.message(chatId, req.id);
                if (message.action == null || message.action.wallpaper == null) { r.fail(400, "WALLPAPER_NOT_FOUND"); return; }
                choice = message.action.wallpaper;
                if (req.settings != null) choice = state(choice.wallpaper, req.settings);
            } else {
                choice = choice(runtime, req.wallpaper, req.settings);
            }
            mutate(runtime, r, req.peer, FoxMesJson.body().put("wallpaper", FoxMesJson.gson.toJsonTree(requestState(choice))).put("for_both", req.for_both));
        });
        transport.on(TL_account.uploadWallPaper.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.uploadWallPaper req = (TL_account.uploadWallPaper) r.request;
            File file = FoxMesFiles.assemble(runtime.account, req.file);
            try {
                MultipartBody body = new MultipartBody.Builder("FoxMes-" + FoxMesJson.operationId()).setType(MultipartBody.FORM)
                    .addFormDataPart("file", "wallpaper.jpg", RequestBody.create(file, MediaType.parse(req.mime_type))).build();
                String raw = runtime.api.http.upload("POST", "/upload/wallpaper-chat", null, body, null, new String[1], 120, null);
                JsonObject upload = FoxMesJson.gson.fromJson(raw, JsonObject.class);
                JsonObject data = upload.getAsJsonObject("data");
                if (data == null || !data.has("sha256")) throw new IOException("wallpaper upload has no identity");
                String sha = data.get("sha256").getAsString();
                Gallery gallery = runtime.api.http.request("GET", "/wallpapers", null, null, Gallery.class);
                for (Wallpaper paper : FoxMesModels.list(gallery.items)) if (sha.equals(paper.sha256)) { r.reply(wallpaper(runtime, state(paper, req.settings))); return; }
                throw new IOException("uploaded wallpaper absent from gallery");
            } finally { file.delete(); }
        });
        transport.on(TL_account.installWallPaper.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.installWallPaper req = (TL_account.installWallPaper) r.request;
            saveDefault(runtime, choice(runtime, req.wallpaper, req.settings)); r.reply(FoxMesHandlers.boolTrue());
        });
        transport.on(TL_account.saveWallPaper.class, FoxMesTransport.Lane.SERIAL, r -> {
            TL_account.saveWallPaper req = (TL_account.saveWallPaper) r.request;
            WallpaperState choice = choice(runtime, req.wallpaper, req.settings);
            if (req.unsave && choice.wallpaper != null && choice.wallpaper.sha256 != null) {
                runtime.api.http.request("DELETE", "/wallpapers/" + choice.wallpaper.sha256, null, null, JsonObject.class);
            }
            r.reply(FoxMesHandlers.boolTrue());
        });
        transport.on(TL_account.resetWallPapers.class, FoxMesTransport.Lane.SERIAL, r -> {
            saveDefault(runtime, state(null, null));r.reply(FoxMesHandlers.boolTrue());
        });
    }
    static TL_account.Tl_chatThemes emptyGiftThemes() {
        TL_account.Tl_chatThemes result = new TL_account.Tl_chatThemes();
        result.hash = 1;
        result.themes = new ArrayList<>();
        result.users = new ArrayList<>();
        result.chats = new ArrayList<>();
        return result;
    }
    static void refreshThemes(FoxMesRuntime runtime) {
        String owner = scope(runtime);
        AndroidUtilities.runOnUIThread(() -> {
            if (!owner.equals(scope(runtime))) return;
            ChatThemeController controller = ChatThemeController.getInstance(runtime.account);
            controller.clearCache();
            controller.requestAllChatThemes(result -> {}, false);
            Theme.loadRemoteThemes(runtime.account, true);
        });
    }
    private static Catalog catalog(FoxMesRuntime runtime) throws IOException {
        String owner = scope(runtime);
        Catalog catalog = runtime.api.http.request("GET", "/chat-themes", null, null, Catalog.class);
        if (!owner.equals(scope(runtime))) return catalog;
        Map<String, String> aliases = new HashMap<>();
        for (ThemeDefinition item : FoxMesModels.list(catalog.items)) {
            if (item.emoticon == null) continue;
            aliases.put(item.emoticon, item.emoticon);
            for (String alias : FoxMesModels.list(item.aliases)) if (alias != null) aliases.put(alias, item.emoticon);
        }
        Map<String, String> previous = themeAliases.put(owner, aliases);
        ApplicationLoader.applicationContext.getSharedPreferences("foxmes_theme_aliases", android.content.Context.MODE_PRIVATE)
            .edit().putString(owner, FoxMesJson.gson.toJson(aliases)).commit();
        if (!aliases.equals(previous)) {
            for (Map.Entry<String, Appearance> entry : states.entrySet()) {
                if (entry.getKey().startsWith(owner)) apply(runtime, Long.parseLong(entry.getKey().substring(owner.length())), entry.getValue(), null);
            }
        }
        return catalog;
    }
    private static String canonicalEmoticon(FoxMesRuntime runtime, String emoticon) {
        if (emoticon == null || emoticon.isEmpty()) return emoticon;
        String owner = scope(runtime);
        Map<String, String> aliases = themeAliases.get(owner);
        if (aliases == null) {
            JsonObject stored = FoxMesJson.gson.fromJson(ApplicationLoader.applicationContext
                .getSharedPreferences("foxmes_theme_aliases", android.content.Context.MODE_PRIVATE).getString(owner, "{}"), JsonObject.class);
            aliases = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : stored.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) aliases.put(entry.getKey(), entry.getValue().getAsString());
            }
            Map<String, String> concurrent = themeAliases.putIfAbsent(owner, aliases);
            if (concurrent != null) aliases = concurrent;
        }
        return aliases.getOrDefault(emoticon, emoticon);
    }
    private static Settings themeSettings(FoxMesRuntime runtime, TLRPC.TL_inputThemeSettings input) throws IOException {
        Settings result = new Settings();
        result.base_theme = input.base_theme instanceof TLRPC.TL_baseThemeTinted ? "tinted" : input.base_theme instanceof TLRPC.TL_baseThemeNight ? "night" : input.base_theme instanceof TLRPC.TL_baseThemeClassic ? "classic" : "day";
        result.accent_color = input.accent_color & 0xffffff;
        result.outgoing_accent_color = (input.flags & 8) != 0 ? input.outbox_accent_color & 0xffffff : null;
        result.message_colors = new ArrayList<>(); for (int color : input.message_colors) result.message_colors.add(color & 0xffffff);
        result.animate_message_colors = input.message_colors_animated;
        if ((input.flags & 2) != 0) result.wallpaper = requestWallpaper(choice(runtime, input.wallpaper, input.wallpaper_settings).wallpaper);
        return result;
    }
    static TLRPC.TL_theme theme(FoxMesRuntime runtime, ThemeDefinition source) {
        return nativeTheme(source, state -> wallpaper(runtime, state));
    }
    private static TLRPC.TL_theme settingsTheme(FoxMesRuntime runtime, ThemeDefinition source) {
        ThemeDefinition expanded = FoxMesJson.parse(FoxMesJson.gson.toJson(source), ThemeDefinition.class);
        expanded.settings = new ArrayList<>();
        for (String base : new String[] {"classic", "day", "night", "tinted"}) {
            Settings selected = null;
            for (Settings settings : FoxMesModels.list(source.settings)) {
                if (base.equals(settings.base_theme)) { selected = settings; break; }
            }
            if (selected == null) {
                String fallback = "classic".equals(base) || "day".equals(base) ? "classic" : "tinted";
                for (Settings settings : FoxMesModels.list(source.settings)) {
                    if (fallback.equals(settings.base_theme)) { selected = settings; break; }
                }
            }
            if (selected != null) {
                Settings settings = FoxMesJson.parse(FoxMesJson.gson.toJson(selected), Settings.class);
                settings.base_theme = base;
                expanded.settings.add(settings);
            }
        }
        return theme(runtime, expanded);
    }
    static TLRPC.TL_theme nativeTheme(ThemeDefinition source, java.util.function.Function<WallpaperState, TLRPC.WallPaper> wallpaperMapper) {
        TLRPC.TL_theme result = new TLRPC.TL_theme();
        result.id = (source.emoticon == null ? 1000000000 : 900000000) + source.id; result.access_hash = 0; result.slug = (source.emoticon == null ? "foxmes-" : "foxmes-chat-") + source.id;
        result.title = source.title; result.emoticon = source.emoticon; result.creator = source.emoticon == null; result.isDefault = source.emoticon != null; result.flags |= 8;
        if (source.emoticon != null) result.flags |= 64;
        for (Settings sourceSettings : FoxMesModels.list(source.settings)) {
            TLRPC.TL_themeSettings setting = new TLRPC.TL_themeSettings();
            setting.base_theme = "tinted".equals(sourceSettings.base_theme) ? new TLRPC.TL_baseThemeTinted() : "night".equals(sourceSettings.base_theme) ? new TLRPC.TL_baseThemeNight() : "classic".equals(sourceSettings.base_theme) ? new TLRPC.TL_baseThemeClassic() : new TLRPC.TL_baseThemeDay();
            setting.accent_color = sourceSettings.accent_color;
            if (sourceSettings.outgoing_accent_color != null) { setting.outbox_accent_color = sourceSettings.outgoing_accent_color; setting.flags |= 8; }
            setting.message_colors.addAll(FoxMesModels.list(sourceSettings.message_colors));
            if (!setting.message_colors.isEmpty()) setting.flags |= 1;
            setting.message_colors_animated = sourceSettings.animate_message_colors;
            if (sourceSettings.wallpaper != null) { setting.wallpaper = wallpaperMapper.apply(state(sourceSettings.wallpaper, null)); setting.flags |= 2; }
            result.settings.add(setting);
        }
        return result;
    }
    static WallpaperState state(Wallpaper paper, TLRPC.WallPaperSettings settings) {
        WallpaperState result = new WallpaperState();
        paper = paper == null ? null : FoxMesJson.parse(FoxMesJson.gson.toJson(paper), Wallpaper.class);
        result.wallpaper = paper;
        if (settings != null) {
            result.blurred = settings.blur; result.intensity = settings.intensity;
            if (paper != null) {
                paper.colors = new ArrayList<>();
                if ((settings.flags & 1) != 0) paper.colors.add(settings.background_color & 0xffffff);
                if ((settings.flags & 16) != 0) paper.colors.add(settings.second_background_color & 0xffffff);
                if ((settings.flags & 32) != 0) paper.colors.add(settings.third_background_color & 0xffffff);
                if ((settings.flags & 64) != 0) paper.colors.add(settings.fourth_background_color & 0xffffff);
                paper.rotation = settings.rotation;paper.motion = settings.motion;paper.intensity = settings.intensity;
            }
        } else if (paper != null) result.intensity = paper.intensity;
        return result;
    }
    static TLRPC.WallPaper wallpaper(FoxMesRuntime runtime, WallpaperState state) {
        Wallpaper source = state != null ? state.wallpaper : null;
        TLRPC.WallPaper result = nativeWallpaper(state, source != null ? FoxMesHttp.absolute(source.url) : null, FoxMesHandlers.now(), Theme.getColor(Theme.key_chat_wallpaper));
        if (result instanceof TLRPC.TL_wallPaper) papers.put(scope(runtime) + result.id, source);
        return result;
    }
    static TLRPC.WallPaper nativeWallpaper(WallpaperState state, String url, int date, int noBackgroundColor) {
        if (state == null || state.wallpaper == null) {
            if (state == null || !state.none) return null;
            TLRPC.TL_wallPaperNoFile empty = new TLRPC.TL_wallPaperNoFile();empty.settings = new TLRPC.TL_wallPaperSettings();empty.settings.flags = 1;empty.settings.background_color = noBackgroundColor & 0xffffff;empty.flags = 4;return empty;
        }
        Wallpaper source = state.wallpaper;
        boolean pattern = "pattern".equals(source.kind);
        String identity = pattern ? "foxmes-pattern:" + source.pattern_id : source.sha256;
        TLRPC.WallPaper result;
        if ("fill".equals(source.kind) || "gradient".equals(source.kind)) result = new TLRPC.TL_wallPaperNoFile();
        else {
            TLRPC.TL_wallPaper paper = new TLRPC.TL_wallPaper();
            paper.id = identity == null ? 0 : identity.hashCode() & 0x7fffffffL;paper.slug = identity;paper.pattern = pattern;paper.dark = state.intensity < 0;
            paper.document = new TLRPC.TL_document();paper.document.id = paper.id;paper.document.dc_id = FoxMesTL.DC;paper.document.access_hash = FoxMesTL.accessHash(paper.id);paper.document.date = date;paper.document.size = source.size;paper.document.mime_type = source.mime != null ? source.mime : pattern ? "application/x-tgwallpattern" : "image/jpeg";
            FoxMesFileRef ref = new FoxMesFileRef();ref.url = url;ref.sha256 = source.sha256;ref.fileUniqueId = identity;ref.mime = paper.document.mime_type;ref.name = pattern ? "pattern.tgv" : "wallpaper.jpg";
            ref.width = source.width;
            ref.height = source.height;
            ref.posterUrl = pattern ? ref.url + "?format=png" : ref.url;
            TLRPC.TL_photoSize thumbnail = new TLRPC.TL_photoSize();
            thumbnail.type = "m";
            thumbnail.w = 320;
            thumbnail.h = source.width > 0 ? Math.max(1, source.height * 320 / source.width) : 320;
            thumbnail.location = new TLRPC.TL_fileLocationToBeDeprecated();
            thumbnail.location.volume_id = paper.id;
            thumbnail.location.local_id = 1;
            thumbnail.location.dc_id = FoxMesTL.DC;
            paper.document.thumbs.add(thumbnail);
            paper.document.flags |= 1;
            paper.document.file_reference = ref.encode();
            TLRPC.TL_documentAttributeImageSize dimensions = new TLRPC.TL_documentAttributeImageSize();dimensions.w = source.width > 0 ? source.width : 1440;dimensions.h = source.height > 0 ? source.height : 1440;paper.document.attributes.add(dimensions);
            TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();filename.file_name = ref.name;paper.document.attributes.add(filename);
            result = paper;
        }
        result.flags |= 4;result.settings = new TLRPC.TL_wallPaperSettings();
        TLRPC.WallPaperSettings settings = result.settings;settings.blur = state.blurred;settings.motion = source.motion;settings.intensity = state.intensity;settings.flags |= 8;settings.rotation = source.rotation;
        List<Integer> colors = FoxMesModels.list(source.colors);
        if (colors.size() > 0) {settings.background_color = colors.get(0);settings.flags |= 1;}
        if (colors.size() > 1) {settings.second_background_color = colors.get(1);settings.flags |= 16;}
        if (colors.size() > 2) {settings.third_background_color = colors.get(2);settings.flags |= 32;}
        if (colors.size() > 3) {settings.fourth_background_color = colors.get(3);settings.flags |= 64;}
        return result;
    }
    private static WallpaperState choice(FoxMesRuntime runtime, TLRPC.InputWallPaper input, TLRPC.WallPaperSettings settings) throws IOException {
        if (input == null) return state(null, settings);
        if (input instanceof TLRPC.TL_inputWallPaperNoFile) {
            Wallpaper paper = new Wallpaper();
            paper.kind = settings != null && (settings.flags & 16) != 0 ? "gradient" : "fill";
            return state(paper, settings);
        }
        String slug = input instanceof TLRPC.TL_inputWallPaperSlug ? ((TLRPC.TL_inputWallPaperSlug) input).slug : null;
        Wallpaper paper = input instanceof TLRPC.TL_inputWallPaper ? papers.get(scope(runtime) + ((TLRPC.TL_inputWallPaper) input).id) : null;
        if (paper == null && slug != null) {
            for (Map.Entry<String, Wallpaper> entry : papers.entrySet()) {
                if (entry.getKey().startsWith(scope(runtime)) && matches(entry.getValue(), input)) { paper = entry.getValue(); break; }
            }
        }
        if (paper == null && (slug == null || !slug.startsWith("foxmes-pattern:"))) {
            Gallery gallery = runtime.api.http.request("GET", "/wallpapers", null, null, Gallery.class);
            for (Wallpaper item : FoxMesModels.list(gallery.items)) if (matches(item, input)) { paper = item; break; }
        }
        if (paper == null && (slug == null || slug.startsWith("foxmes-pattern:"))) {
            Catalog catalog = catalog(runtime);
            for (ThemeDefinition definition : FoxMesModels.list(catalog.items)) {
                for (Settings variant : FoxMesModels.list(definition.settings)) {
                    if (variant.wallpaper != null && matches(variant.wallpaper, input)) { paper = variant.wallpaper; break; }
                }
                if (paper != null) break;
            }
        }
        if (paper == null) throw new FoxMesHttpException(400, "WALLPAPER_NOT_FOUND", null, "");
        return state(paper, settings);
    }
    private static boolean matches(Wallpaper paper, TLRPC.InputWallPaper input) {
        String identity = "pattern".equals(paper.kind) ? "foxmes-pattern:" + paper.pattern_id : paper.sha256;
        if (identity == null) return false;
        if (input instanceof TLRPC.TL_inputWallPaperSlug) return identity.equals(((TLRPC.TL_inputWallPaperSlug) input).slug);
        return input instanceof TLRPC.TL_inputWallPaper && ((TLRPC.TL_inputWallPaper) input).id == (identity.hashCode() & 0x7fffffffL);
    }
    private static Wallpaper requestWallpaper(Wallpaper source) {
        if (source == null) return null;
        Wallpaper result = FoxMesJson.parse(FoxMesJson.gson.toJson(source), Wallpaper.class);
        if (!"photo".equals(result.kind) && result.sha256 == null) result.url = null;
        return result;
    }
    private static WallpaperState requestState(WallpaperState source) {
        WallpaperState result = FoxMesJson.parse(FoxMesJson.gson.toJson(source), WallpaperState.class);
        result.wallpaper = requestWallpaper(source.wallpaper);
        return result;
    }
    static Appearance merge(FoxMesRuntime runtime, long chatId, Appearance incoming) {
        String key = scope(runtime) + chatId;
        synchronized (states) {
            Appearance current = states.get(key);Appearance result = new Appearance();
            result.theme = current != null && (incoming.theme == null || incoming.theme.revision < current.theme.revision) ? current.theme : incoming.theme;
            result.wallpaper = current != null && (incoming.wallpaper == null || incoming.wallpaper.settings_revision < current.wallpaper.settings_revision) ? current.wallpaper : incoming.wallpaper;
            if (result.theme == null) {result.theme = new ThemeState();result.theme.theme_emoticon = "";}
            if (result.wallpaper == null) result.wallpaper = state(null, null);
            states.put(key, result);return result;
        }
    }
    static void fillUserFull(FoxMesRuntime runtime, TLRPC.UserFull full, Appearance appearance) {
        if (appearance == null) return;
        String emoji = canonicalEmoticon(runtime, appearance.theme != null ? appearance.theme.theme_emoticon : "");
        if (emoji != null && !emoji.isEmpty()) {TLRPC.TL_chatTheme theme = new TLRPC.TL_chatTheme();theme.emoticon = emoji;full.theme = theme;full.flags |= 32768;}
        else {full.theme = null;full.flags &= ~32768;}
        full.wallpaper = wallpaper(runtime, appearance.wallpaper);
        if (full.wallpaper != null) full.flags |= 16777216;else full.flags &= ~16777216;
    }
    static void apply(FoxMesRuntime runtime, long chatId, Appearance incoming, Runnable afterApplied) {
        Appearance merged = merge(runtime, chatId, incoming);
        String key = scope(runtime) + chatId;
        Chat chat = runtime.chat(chatId);if (chat != null) chat.appearance = merged;
        long peer = runtime.peerForChat(chatId);if (peer == 0) {if (afterApplied != null) afterApplied.run();return;}
        AndroidUtilities.runOnUIThread(() -> {
            if (!key.equals(scope(runtime) + chatId)) return;
            Appearance confirmed = states.getOrDefault(key, merged);
            TLRPC.TL_chatTheme theme = new TLRPC.TL_chatTheme();theme.emoticon = canonicalEmoticon(runtime, confirmed.theme.theme_emoticon);
            ChatThemeController themeController = ChatThemeController.getInstance(runtime.account);
            themeController.setDialogTheme(peer, theme.emoticon == null || theme.emoticon.isEmpty() ? null : theme, false);
            MessagesController controller = MessagesController.getInstance(runtime.account);
            MessagesStorage storage = MessagesStorage.getInstance(runtime.account);
            TLRPC.UserFull full = controller.getUserFull(peer);
            if (full != null) {
                fillUserFull(runtime, full, confirmed);
                full.wallpaper_overridden = false;
                storage.updateUserInfo(full, false);
                themeController.saveChatWallpaper(peer, full.wallpaper);
            } else {
                themeController.saveChatWallpaper(peer, wallpaper(runtime, confirmed.wallpaper));
                controller.loadFullUser(controller.getUser(peer), 0, true);
            }
            storage.getStorageQueue().postRunnable(() -> AndroidUtilities.runOnUIThread(() -> {
                if (!key.equals(scope(runtime) + chatId)) return;
                if (full != null) NotificationCenter.getInstance(runtime.account).postNotificationName(NotificationCenter.userInfoDidLoad, peer, full);
                if (afterApplied != null) afterApplied.run();
            }));
        });
    }
    static TLRPC.MessageAction action(FoxMesRuntime runtime, Action source) {
        if ("theme_changed".equals(source.type)) {TLRPC.TL_messageActionSetChatTheme action = new TLRPC.TL_messageActionSetChatTheme();TLRPC.TL_chatTheme theme = new TLRPC.TL_chatTheme();theme.emoticon = canonicalEmoticon(runtime, source.theme_emoticon == null ? "" : source.theme_emoticon);action.theme = theme;return action;}
        if ("wallpaper_changed".equals(source.type)) {TLRPC.TL_messageActionSetChatWallPaper action = new TLRPC.TL_messageActionSetChatWallPaper();action.for_both = source.for_both;action.wallpaper = wallpaper(runtime, source.wallpaper);if (action.wallpaper == null) {action.wallpaper = new TLRPC.TL_wallPaperNoFile();action.wallpaper.settings = new TLRPC.TL_wallPaperSettings();}return action;}
        return null;
    }
    private static void mutate(FoxMesRuntime runtime, FoxMesRequest r, TLRPC.InputPeer peer, FoxMesJson.Body body) throws IOException {
        long chatId = runtime.ensureChatId(FoxMesHandlers.peerUserId(peer, runtime));
        AppearanceResult result = operation(runtime, "PUT", "/chats/" + chatId + "/appearance", body, AppearanceResult.class);
        apply(runtime, chatId, result, null);
        ArrayList<TLRPC.Update> updates = new ArrayList<>();
        if (result.message != null && runtime.store().revisions(result.message.id) == null) {TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();update.message = runtime.tlMessage(result.message);updates.add(update);}
        r.replyLate(() -> runtime.updates.container(updates, runtime.tlUsers(Collections.singletonList(runtime.peerForChat(chatId)))));
    }
    private static void saveDefault(FoxMesRuntime runtime, WallpaperState choice) throws IOException {
        FoxMesJson.Body body = FoxMesJson.body().put("wallpaper", FoxMesJson.gson.toJsonTree(requestWallpaper(choice.wallpaper))).put("blurred", choice.blurred).put("intensity", choice.intensity).put("none", choice.none).put("operation_id", FoxMesJson.operationId());
        WallpaperState confirmed = operation(runtime, "PUT", "/wallpaper", body, WallpaperState.class);
        applyDefault(runtime, confirmed, null);
    }

    private static final Map<String, Long> defaultVersions = new ConcurrentHashMap<>();
    private static String activeDefaultScope;
    private static long activeDefaultRevision = -1;
    private static Theme.ThemeInfo activeDefaultTheme;
    static void refreshDefault(FoxMesRuntime runtime) {
        try { applyDefault(runtime, runtime.api.http.request("GET", "/wallpaper", null, null, WallpaperState.class), null); }
        catch (IOException error) { FoxMesLog.w("default wallpaper: " + error.getMessage()); }
    }
    static void applyDefault(FoxMesRuntime runtime, WallpaperState state, Runnable done) throws IOException {
        applyDefault(runtime, state, done, false);
    }
    private static void applyDefault(FoxMesRuntime runtime, WallpaperState state, Runnable done, boolean restore) throws IOException {
        if (state == null) { if (done != null) done.run(); return; }
        String key = scope(runtime);
        if (state.appearance != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!key.equals(scope(runtime))) return;
                if (UserConfig.selectedAccount == runtime.account) {
                    android.content.SharedPreferences seen = ApplicationLoader.applicationContext.getSharedPreferences("foxmes_appearance_hints", android.content.Context.MODE_PRIVATE);
                    if (seen.getBoolean(key, false) && !seen.getBoolean(key + "native_empty_background", false)) {
                        Theme.ThemeInfo active = Theme.getActiveTheme();
                        Theme.OverrideWallpaperInfo old = active.overrideWallpaper;
                        if (state.appearance.wallpaper_none && "Dark Blue".equals(active.name) && old != null
                            && Theme.COLOR_BACKGROUND_SLUG.equals(old.slug) && old.color == 0xff000000
                            && old.gradientColor1 == 0 && android.text.TextUtils.isEmpty(old.fileName) && android.text.TextUtils.isEmpty(old.originalFileName)) {
                            Theme.OverrideWallpaperInfo corrected = new Theme.OverrideWallpaperInfo(old, active, active.getAccent(false));
                            corrected.color = Theme.getColor(Theme.key_chat_wallpaper);
                            active.setOverrideWallpaper(corrected);
                            Theme.reloadWallpaper(true);
                        }
                        seen.edit().putBoolean(key + "native_empty_background", true).apply();
                    }
                    if (!seen.getBoolean(key, false)) {
                        android.content.SharedPreferences local = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", android.content.Context.MODE_PRIVATE);
                        if (!local.contains("theme") && !local.contains("nighttheme") && Theme.getActiveTheme().overrideWallpaper == null) {
                            String name = "classic".equals(state.appearance.theme) ? "Blue" : "day".equals(state.appearance.theme) ? "Arctic Blue" : "night".equals(state.appearance.theme) ? "Dark" : "Dark Blue";
                            Theme.ThemeInfo theme = Theme.getTheme(name);
                            if (theme != null) Theme.applyTheme(theme, true, false);
                            if (state.appearance.wallpaper_none) { Theme.OverrideWallpaperInfo none = new Theme.OverrideWallpaperInfo(); none.slug = Theme.COLOR_BACKGROUND_SLUG; none.color = Theme.getColor(Theme.key_chat_wallpaper); Theme.getActiveTheme().setOverrideWallpaper(none); Theme.reloadWallpaper(true); }
                        }
                        seen.edit().putBoolean(key, true).commit();
                    }
                }
                if (done != null) done.run();
            });
            return;
        }
        long current = defaultVersions.getOrDefault(key, -1L);
        if (state.settings_revision < current || !restore && state.settings_revision == current && state.settings_revision == activeDefaultRevision && key.equals(activeDefaultScope) && Theme.getActiveTheme() == activeDefaultTheme) { if (done != null) done.run(); return; }
        Theme.OverrideWallpaperInfo info = null;
        Wallpaper paper = state.wallpaper;
        if (paper != null || state.none) {
            info = new Theme.OverrideWallpaperInfo();
            info.slug = paper == null || "fill".equals(paper.kind) || "gradient".equals(paper.kind) ? Theme.COLOR_BACKGROUND_SLUG : paper.pattern_id != null ? "foxmes-pattern:" + paper.pattern_id : paper.sha256;
            info.color = 0;
            List<Integer> colors = paper == null ? Collections.emptyList() : FoxMesModels.list(paper.colors);
            if (colors.size() > 0) info.color = colors.get(0) | 0xff000000;
            if (colors.size() > 1) info.gradientColor1 = colors.get(1) | 0xff000000;
            if (colors.size() > 2) info.gradientColor2 = colors.get(2) | 0xff000000;
            if (colors.size() > 3) info.gradientColor3 = colors.get(3) | 0xff000000;
            info.rotation = paper == null ? 0 : paper.rotation; info.isMotion = paper != null && paper.motion;
            info.isBlurred = state.blurred; info.intensity = state.intensity / 100f;
            if (paper != null && paper.url != null && !paper.url.isEmpty()) {
                String name = "foxmes-wallpaper-" + runtime.account + "-" + runtime.selfId() + "-" + FoxMesJson.operationId() + ".jpg";
                File file = new File(ApplicationLoader.getFilesDirFixed(), name);
                try (Response response = runtime.http.download(FoxMesHttp.absolute(paper.url), 0, 0)) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("wallpaper download failed");
                    try (FileOutputStream output = new FileOutputStream(file)) { output.write(response.body().bytes()); }
                }
                if ("pattern".equals(paper.kind)) {
                    Bitmap bitmap;
                    try { bitmap = decodePattern(file); }
                    catch (IOException error) { file.delete(); throw error; }
                    if (bitmap == null) { file.delete(); throw new IOException("invalid wallpaper pattern"); }
                    if (colors.size() < 3) {
                        Bitmap composed = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                        android.graphics.Canvas canvas = new android.graphics.Canvas(composed);
                        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                        paint.setAlpha(Math.round(255 * Math.abs(info.intensity)));
                        int layer = -1;
                        if (info.intensity < 0) {
                            canvas.drawColor(android.graphics.Color.BLACK);
                            layer = canvas.saveLayer(0, 0, composed.getWidth(), composed.getHeight(), paint);
                        }
                        if (colors.size() == 2) {
                            android.graphics.drawable.GradientDrawable gradient = new android.graphics.drawable.GradientDrawable(org.telegram.ui.Components.BackgroundGradientDrawable.getGradientOrientation(info.rotation), new int[]{info.color, info.gradientColor1});
                            gradient.setBounds(0, 0, composed.getWidth(), composed.getHeight());
                            gradient.draw(canvas);
                        } else { canvas.drawColor(info.color); }
                        if (info.intensity < 0) {
                            paint.setAlpha(255);
                            paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                            canvas.drawBitmap(bitmap, 0, 0, paint);
                            canvas.restoreToCount(layer);
                        } else {
                            int patternColor = AndroidUtilities.getPatternColor(colors.size() == 2 ? AndroidUtilities.getAverageColor(info.color, info.gradientColor1) : info.color);
                            paint.setColorFilter(new android.graphics.PorterDuffColorFilter(patternColor, android.graphics.PorterDuff.Mode.SRC_IN));
                            canvas.drawBitmap(bitmap, 0, 0, paint);
                        }
                        bitmap.recycle(); bitmap = composed;
                    }
                    try (FileOutputStream output = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
                    finally { bitmap.recycle(); }
                }
                info.fileName = info.originalFileName = name;
                if (state.blurred && !"pattern".equals(paper.kind)) {
                    Bitmap decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (decoded == null) { file.delete(); throw new IOException("invalid wallpaper photo"); }
                    Bitmap small = Bitmap.createScaledBitmap(decoded, Math.max(1, decoded.getWidth() * 120 / Math.max(decoded.getWidth(), decoded.getHeight())), Math.max(1, decoded.getHeight() * 120 / Math.max(decoded.getWidth(), decoded.getHeight())), true);
                    Bitmap blurred = small.copy(Bitmap.Config.ARGB_8888, true);
                    if (small != decoded) small.recycle(); decoded.recycle();
                    org.telegram.messenger.Utilities.blurBitmap(blurred, 3);
                    info.fileName = name + "-blur.jpg";
                    try (FileOutputStream output = new FileOutputStream(new File(ApplicationLoader.getFilesDirFixed(), info.fileName))) { blurred.compress(Bitmap.CompressFormat.JPEG, 90, output); }
                    finally { blurred.recycle(); }
                }
            }
        }
        final Theme.OverrideWallpaperInfo confirmed = info;
        synchronized (defaultVersions) {
            if (state.settings_revision < defaultVersions.getOrDefault(key, -1L)) {
                retireDefaultFiles(confirmed);
                if (done != null && key.equals(scope(runtime))) done.run();
                return;
            }
            defaultVersions.put(key, state.settings_revision);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!key.equals(scope(runtime))) { retireDefaultFiles(confirmed); return; }
            if (state.settings_revision < defaultVersions.getOrDefault(key, -1L)) {
                retireDefaultFiles(confirmed);
                if (done != null) done.run();
                return;
            }
            if (UserConfig.selectedAccount == runtime.account) {
                activeDefaultTheme = Theme.getActiveTheme();
                if (state.none && confirmed != null) confirmed.color = Theme.getColor(Theme.key_chat_wallpaper);
                activeDefaultTheme.setOverrideWallpaper(confirmed);
                Theme.reloadWallpaper(true);
                activeDefaultRevision = state.settings_revision; activeDefaultScope = key;
            } else { retireDefaultFiles(confirmed); }
            if (done != null) done.run();
        });
    }
    private static Bitmap decodePattern(File file) throws IOException {
        File decoded = new File(file.getParentFile(), file.getName() + ".svg");
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            input.mark(2);
            boolean compressed = input.read() == 0x1f && input.read() == 0x8b;
            input.reset();
            if (!compressed) return SvgHelper.getBitmap(file, 1440, 1440, false, SvgHelper.ScaleMode.ByWidth);
            try (GZIPInputStream gzip = new GZIPInputStream(input)) {
                if (!FileLoader.copyFile(gzip, decoded, 2 * 1024 * 1024)) throw new IOException("pattern decode failed");
            }
            return SvgHelper.getBitmap(decoded, 1440, 1440, false, SvgHelper.ScaleMode.ByWidth);
        } finally { decoded.delete(); }
    }
    private static void retireDefaultFiles(Theme.OverrideWallpaperInfo info) {
        if (info == null) return;
        if (info.fileName != null) new File(ApplicationLoader.getFilesDirFixed(), info.fileName).delete();
        if (info.originalFileName != null) new File(ApplicationLoader.getFilesDirFixed(), info.originalFileName).delete();
    }
    public static void savePeerWallpaper(int account, File file, Theme.OverrideWallpaperInfo info, Runnable confirmed) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.serial.execute(() -> {
            try {
                long chatId = runtime.ensureChatId(info.dialogId);
                MultipartBody upload = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "wallpaper.jpg", RequestBody.create(file, MediaType.parse("image/jpeg"))).build();
                JsonObject response = FoxMesJson.gson.fromJson(runtime.http.upload("POST", "/upload/wallpaper-chat", null, upload, null, new String[1], 120, null), JsonObject.class);
                String sha = response.getAsJsonObject("data").get("sha256").getAsString();
                Gallery gallery = runtime.http.request("GET", "/wallpapers", null, null, Gallery.class);
                Wallpaper paper = null;
                for (Wallpaper candidate : FoxMesModels.list(gallery.items)) if (sha.equals(candidate.sha256)) { paper = candidate; break; }
                if (paper == null) throw new IOException("uploaded wallpaper missing");
                paper.motion = info.isMotion;
                WallpaperState choice = state(paper, null); choice.blurred = info.isBlurred; choice.intensity = Math.round(info.intensity * 100);
                FoxMesJson.Body body = FoxMesJson.body().put("wallpaper", FoxMesJson.gson.toJsonTree(choice)).put("for_both", info.forBoth);
                AppearanceResult result = operation(runtime, "PUT", "/chats/" + chatId + "/appearance", body, AppearanceResult.class);
                ArrayList<TLRPC.Update> updates = new ArrayList<>();
                if (result.message != null && runtime.store().revisions(result.message.id) == null) { TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage(); update.message = runtime.tlMessage(result.message); updates.add(update); }
                runtime.updates.dispatch(updates, runtime.tlUsers(Collections.singletonList(info.dialogId)), null);
                apply(runtime, chatId, result, () -> AndroidUtilities.runOnUIThread(confirmed));
            } catch (Exception error) { failure(runtime, error); }
        });
    }
    public static void saveGlobalWallpaper(int account, File file, Theme.OverrideWallpaperInfo info, boolean install) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.serial.execute(() -> {
            try {
                Wallpaper paper;
                if (file != null) {
                    MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "wallpaper.jpg", RequestBody.create(file, MediaType.parse("image/jpeg"))).build();
                    JsonObject upload = FoxMesJson.gson.fromJson(runtime.http.upload("POST", "/upload/wallpaper-chat", null, body, null, new String[1], 120, null), JsonObject.class);
                    String sha = upload.getAsJsonObject("data").get("sha256").getAsString();
                    Gallery gallery = runtime.http.request("GET", "/wallpapers", null, null, Gallery.class);
                    paper = null; for (Wallpaper candidate : FoxMesModels.list(gallery.items)) if (sha.equals(candidate.sha256)) {paper = candidate;break;}
                    if (paper == null) throw new IOException("uploaded wallpaper missing");
                } else if (info.isDefault() || info.isTheme()) { paper = null; }
                else if (info.isColor()) { paper = new Wallpaper();paper.kind = info.gradientColor1 == 0 ? "fill" : "gradient"; }
                else {
                    TLRPC.TL_inputWallPaperSlug input = new TLRPC.TL_inputWallPaperSlug();input.slug = info.slug;
                    paper = choice(runtime, input, null).wallpaper;
                }
                TLRPC.TL_wallPaperSettings settings = new TLRPC.TL_wallPaperSettings();settings.blur = info.isBlurred;settings.motion = info.isMotion;settings.intensity = Math.round(info.intensity * 100);settings.rotation = info.rotation;settings.flags = 8;
                if (info.color != 0) {settings.flags |= 1; settings.background_color = info.color;}
                if (info.gradientColor1 != 0) {settings.flags |= 16; settings.second_background_color = info.gradientColor1;}
                if (info.gradientColor2 != 0) {settings.flags |= 32; settings.third_background_color = info.gradientColor2;}
                if (info.gradientColor3 != 0) {settings.flags |= 64; settings.fourth_background_color = info.gradientColor3;}
                if (install) saveDefault(runtime, state(paper, settings));
            } catch (Exception error) { failure(runtime, error); }
        });
    }
    public static void saveUserTheme(int account, Theme.ThemeInfo info, Theme.ThemeAccent accent) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.serial.execute(() -> {
            try {
                if (accent == null) throw new IOException("theme settings unavailable");
                Settings settings = new Settings();
                TLRPC.BaseTheme base = Theme.getBaseThemeByKey(info.name);
                settings.base_theme = base instanceof TLRPC.TL_baseThemeNight ? "night" : base instanceof TLRPC.TL_baseThemeTinted ? "tinted" : base instanceof TLRPC.TL_baseThemeClassic ? "classic" : "day";
                settings.accent_color = accent.accentColor & 0xffffff; settings.outgoing_accent_color = accent.accentColor2 == 0 ? null : accent.accentColor2 & 0xffffff;
                settings.message_colors = new ArrayList<>();
                for (int color : new int[]{accent.myMessagesAccentColor,accent.myMessagesGradientAccentColor1,accent.myMessagesGradientAccentColor2,accent.myMessagesGradientAccentColor3}) if (color != 0) settings.message_colors.add(color & 0xffffff);
                settings.animate_message_colors = accent.myMessagesAnimated;
                if (accent.backgroundOverrideColor != 0) {
                    Wallpaper wallpaper = new Wallpaper();wallpaper.colors = new ArrayList<>();
                    for (long color : new long[]{accent.backgroundOverrideColor,accent.backgroundGradientOverrideColor1,accent.backgroundGradientOverrideColor2,accent.backgroundGradientOverrideColor3}) if (color != 0) wallpaper.colors.add((int)color & 0xffffff);
                    wallpaper.kind = wallpaper.colors.size() > 1 ? "gradient" : "fill";
                    if (accent.patternSlug != null && accent.patternSlug.startsWith("foxmes-pattern:")) {wallpaper.kind = "pattern";wallpaper.pattern_id = accent.patternSlug.substring(15);}
                    wallpaper.intensity = Math.round(accent.patternIntensity * 100);wallpaper.rotation = accent.backgroundRotation;wallpaper.motion = accent.patternMotion;settings.wallpaper = wallpaper;
                }
                long id = accent.info != null && accent.info.creator ? accent.info.id - 1000000000 : 0;
                FoxMesJson.Body body = FoxMesJson.body().put("title", info.name).put("settings", FoxMesJson.gson.toJsonTree(Collections.singletonList(settings))).put("operation_id", FoxMesJson.operationId());
                ThemeDefinition saved = operation(runtime, id > 0 ? "PUT" : "POST", id > 0 ? "/themes/" + id : "/themes", body, ThemeDefinition.class);
                AndroidUtilities.runOnUIThread(() -> {
                    Theme.setThemeUploadInfo(info, accent, theme(runtime, saved), account, id > 0);
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.themeUploadedToServer, info, accent);
                });
            } catch (Exception error) {
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.themeUploadError, info, accent));
                failure(runtime, error);
            }
        });
    }
    public static void selectTheme(int account, long dialogId, TLRPC.ChatTheme theme) {
        FoxMesRuntime runtime = FoxMesRuntime.getInstance(account);
        runtime.serial.execute(() -> {
            try {
                long chatId = runtime.ensureChatId(dialogId);
                ThemeKey key = ThemeKey.of(theme);
                FoxMesJson.Body body = FoxMesJson.body().put("theme_emoticon", key == null ? "" : key.emoticon).put("reset_wallpaper", "self").put("operation_id", FoxMesJson.operationId());
                AppearanceResult result = operation(runtime, "PUT", "/chats/" + chatId + "/appearance", body, AppearanceResult.class);
                apply(runtime, chatId, result, null);
                if (result.message != null && runtime.store().revisions(result.message.id) == null) {
                    TL_update.TL_updateNewMessage update = new TL_update.TL_updateNewMessage();update.message = runtime.tlMessage(result.message);
                    runtime.updates.dispatch(Collections.singletonList(update), runtime.tlUsers(Collections.singletonList(result.message.senderId)), null);
                }
            } catch (Exception error) { failure(runtime, error); }
        });
    }
    private static <T> T operation(FoxMesRuntime runtime, String method, String path, FoxMesJson.Body body, Class<T> type) throws IOException {
        String owner = scope(runtime);
        String key = owner + method + path;
        android.content.SharedPreferences pending = ApplicationLoader.applicationContext.getSharedPreferences("foxmes_appearance_pending", android.content.Context.MODE_PRIVATE);
        JsonObject previous = FoxMesJson.gson.fromJson(pending.getString(key, "{}"), JsonObject.class);
        String prior = FoxMesJson.optString(previous, "operation_id"); previous.remove("operation_id"); body.object.remove("operation_id");
        String id = prior != null && previous.equals(body.object) ? prior : FoxMesJson.operationId();
        body.put("operation_id", id); pending.edit().putString(key, body.object.toString()).commit();
        T result;
        try { result = runtime.http.request(method, path, null, body, type); }
            catch (IOException error) {
                if (!owner.equals(scope(runtime))) throw error;
                if (error instanceof FoxMesHttpException && !((FoxMesHttpException) error).isTransient()) { clearPending(pending, key, id); throw error; }
                JsonObject journal = runtime.http.request("GET", "/operations/" + id, null, null, JsonObject.class);
                if ("done".equals(FoxMesJson.optString(journal, "status")) && journal.has("result")) result = FoxMesJson.gson.fromJson(journal.get("result"), type);
                else if ("unknown".equals(FoxMesJson.optString(journal, "status"))) result = runtime.http.request(method, path, null, body, type);
                else throw error;
            }
            if (!owner.equals(scope(runtime))) throw new IOException("appearance account changed");
            clearPending(pending, key, id);
        return result;
    }
    private static void clearPending(android.content.SharedPreferences pending, String key, String id) {
        JsonObject stored = FoxMesJson.gson.fromJson(pending.getString(key, "{}"), JsonObject.class);
        if (id.equals(FoxMesJson.optString(stored, "operation_id"))) pending.edit().remove(key).commit();
    }
    private static void failure(FoxMesRuntime runtime, Exception error) {
        FoxMesLog.e("appearance", error);
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, "Не удалось сохранить оформление", Toast.LENGTH_LONG).show());
        runtime.invalidateSnapshot();
        try {
            runtime.snapshot(true);
            applyDefault(runtime, runtime.http.request("GET", "/wallpaper", null, null, WallpaperState.class), null, true);
        } catch (IOException refreshError) { FoxMesLog.e("appearance refresh", refreshError); }
    }

}

