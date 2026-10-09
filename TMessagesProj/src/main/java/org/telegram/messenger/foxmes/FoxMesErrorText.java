package org.telegram.messenger.foxmes;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class FoxMesErrorText {
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+$");
    private static final Map<String, String> MESSAGES;

    static {
        Map<String, String> messages = new HashMap<>();
        messages.put("CALL_BUSY", "Unable to start the call because a participant is already in another call.");
        messages.put("CALL_PEER_OFFLINE", "The person you're calling is offline. Please try again later.");
        messages.put("CALL_PEER_REFUSES", "This person is not accepting calls.");
        messages.put("USER_PRIVACY_RESTRICTED", "This person is not accepting calls.");
        messages.put("CALL_RELAY_UNAVAILABLE", "Unable to connect the call. Please try again later.");
        messages.put("GROUPCALL_MEDIA_UNAVAILABLE", "Unable to connect the call. Please try again later.");
        messages.put("CALL_PEER_UNSUPPORTED", "The other person's app does not support this call. They may need to update it.");
        messages.put("CALL_VERSION_MISMATCH", "The other person's app does not support this call. They may need to update it.");
        messages.put("PARTICIPANT_VERSION_OUTDATED", "The other person's app does not support this call. They may need to update it.");
        messages.put("CALL_PROTOCOL_LAYER_INVALID", "The other person's app does not support this call. They may need to update it.");
        messages.put("CALL_RESPONSE_INVALID", "Unable to start the call. Please try again later.");
        messages.put("CALLS_UNAVAILABLE", "Unable to start the call. Please try again later.");
        messages.put("CALL_CHAT_UNRESOLVED", "Unable to start the call. Please try again later.");
        messages.put("DH_CONFIG_INVALID", "Unable to start the call. Please try again later.");
        messages.put("CALL_CONFIG_INVALID", "Unable to start the call. Please try again later.");
        messages.put("FOXMES_CALL_FAILED", "Unable to start the call. Please try again later.");
        messages.put("GROUPCALL_FORBIDDEN", "You do not have permission to join or manage this call.");
        messages.put("GROUPCALL_INVALID", "This call is no longer available.");
        messages.put("GROUPCALL_NOT_STARTED", "This call has not started yet.");
        messages.put("GROUPCALL_ALREADY_STARTED", "This chat already has an active call.");
        messages.put("GROUPCALL_PARTICIPANTS_TOO_MUCH", "This call has reached its participant limit.");
        messages.put("GROUPCALL_MESSAGES_DISABLED", "Messages are disabled in this call.");
        messages.put("GROUPCALL_SSRC_DUPLICATE_MUCH", "Unable to connect to this call. Please try again.");
        messages.put("CONF_WRITE_CHAIN_INVALID", "Unable to connect to this call. Please try again.");
        messages.put("BLOCK_INVALID", "Unable to connect to this call. Please try again.");
        messages.put("PARTICIPANT_ID_INVALID", "This participant is no longer in the call.");
        messages.put("GROUPCALL_TITLE_INVALID", "The call title is too long.");
        messages.put("FORBIDDEN", "You do not have permission to perform this action.");
        messages.put("CHAT_ACCESS_DENIED", "You do not have permission to perform this action.");
        messages.put("CHAT_ADMIN_REQUIRED", "Only a chat administrator can perform this action.");
        messages.put("CHAT_WRITE_FORBIDDEN", "You cannot send messages in this chat.");
        messages.put("USER_BANNED_IN_CHANNEL", "You cannot contact this person or post in this chat.");
        messages.put("USER_BLOCKED", "You cannot contact this person or post in this chat.");
        messages.put("YOU_BLOCKED_USER", "Unblock this person before contacting them.");
        messages.put("CHANNEL_PRIVATE", "This chat is no longer available or you do not have access to it.");
        messages.put("CHANNEL_INVALID", "This chat is no longer available or you do not have access to it.");
        messages.put("CHAT_ID_INVALID", "This chat is no longer available or you do not have access to it.");
        messages.put("NOT_FOUND", "The requested item is no longer available.");
        messages.put("MESSAGE_ID_INVALID", "The requested item is no longer available.");
        messages.put("MSG_ID_INVALID", "The requested item is no longer available.");
        messages.put("USERNAME_NOT_OCCUPIED", "The requested item is no longer available.");
        messages.put("UNAUTHORIZED", "Your session has ended. Please sign in again.");
        messages.put("AUTH_KEY_UNREGISTERED", "Your session has ended. Please sign in again.");
        messages.put("SESSION_REVOKED", "Your session has ended. Please sign in again.");
        messages.put("BAD_REQUEST", "Unable to complete this action. Please check your input and try again.");
        messages.put("MESSAGE_INVALID", "Unable to complete this action. Please check your input and try again.");
        messages.put("MESSAGE_EMPTY", "Enter a message or add an attachment.");
        messages.put("MESSAGE_PINNED", "Unpin this message before deleting it.");
        messages.put("MESSAGE_EDIT_FORBIDDEN", "You cannot edit this message.");
        messages.put("MESSAGE_DELETE_FORBIDDEN", "You do not have permission to delete this message.");
        messages.put("EDIT_TIME_EXPIRED", "This message can no longer be edited.");
        messages.put("MESSAGE_EDIT_TIME_EXPIRED", "This message can no longer be edited.");
        messages.put("MESSAGE_EDIT_TIME_EXPIRED_ERROR", "This message can no longer be edited.");
        messages.put("EPHEMERAL_EXPIRED", "This media has expired and is no longer available.");
        messages.put("GONE", "This media has expired and is no longer available.");
        messages.put("PIN_LIMIT", "This chat has reached its pin limit.");
        messages.put("PINNED_DIALOGS_TOO_MUCH", "This chat has reached its pin limit.");
        messages.put("REMINDER_LIMIT", "This chat has reached its scheduled message limit.");
        messages.put("SCHEDULE_TOO_MUCH", "This chat has reached its scheduled message limit.");
        messages.put("REMINDER_NOT_PENDING", "This scheduled message is already being sent.");
        messages.put("SCHEDULE_DATE_INVALID", "Choose a date and time in the future.");
        messages.put("CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("APPEARANCE_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("DRAFT_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("PHOTOS_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("MESSAGE_REVISION_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("REACTION_REVISION_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("REMINDER_REVISION_CONFLICT", "This item has changed. Reload it and try again.");
        messages.put("OPERATION_CONFLICT", "Unable to confirm this action. Reload the current state and try again.");
        messages.put("OPERATION_RESULT_UNAVAILABLE", "Unable to confirm this action. Reload the current state and try again.");
        messages.put("OPERATION_IN_PROGRESS", "This action is still being processed. Please wait.");
        messages.put("REACTION_INVALID", "This reaction is not available for this message.");
        messages.put("REACTIONS_BUSY", "Unable to update reactions right now. Please try again.");
        messages.put("RATE_LIMITED", "Too many attempts. Please wait and try again.");
        messages.put("FLOOD_WAIT", "Too many attempts. Please wait and try again.");
        messages.put("PAYLOAD_TOO_LARGE", "This file is too large. Please choose a smaller file.");
        messages.put("PHOTO_FILE_TOO_BIG", "This file is too large. Please choose a smaller file.");
        messages.put("VIDEO_FILE_TOO_BIG", "This file is too large. Please choose a smaller file.");
        messages.put("WALLPAPER_NOT_FOUND", "This theme or wallpaper is no longer available.");
        messages.put("THEME_INVALID", "This theme or wallpaper is no longer available.");
        messages.put("USERNAME_INVALID", "Enter a valid username.");
        messages.put("USERNAME_OCCUPIED", "This username is already taken.");
        messages.put("CHAT_TITLE_EMPTY", "Enter a chat name.");
        messages.put("CHAT_ABOUT_TOO_LONG", "The chat description is too long.");
        messages.put("CHAT_CREATE_FORBIDDEN", "Your account cannot create this chat.");
        messages.put("CHAT_OWNER_LEAVE", "The chat owner must delete the chat to leave it.");
        messages.put("USERS_TOO_MUCH", "This chat has reached its member limit.");
        messages.put("USERS_TOO_FEW", "Select at least one person.");
        messages.put("USER_ID_INVALID", "This person is not available for this action.");
        messages.put("USER_NOT_PARTICIPANT", "This person is not available for this action.");
        messages.put("USER_ADMIN_INVALID", "This person is not available for this action.");
        messages.put("USER_ALREADY_PARTICIPANT", "This person is already a participant.");
        messages.put("INVITE_HASH_EXPIRED", "This invitation is no longer valid.");
        messages.put("INVITE_HASH_INVALID", "This invitation is no longer valid.");
        messages.put("STICKER_CHAT_ONLY", "This sticker can only be sent in FoxMes.");
        messages.put("MEDIA_NOT_ON_SITE", "Voice and video messages cannot be sent to the site's comments.");
        messages.put("WELCOME_MESSAGES_TOO_MUCH", "This chat has reached its welcome message limit.");
        messages.put("STICKER_FAVORITES_TOO_MUCH", "You have reached the favorite sticker limit.");
        messages.put("NOT_IMPLEMENTED", "This action is not available.");
        messages.put("FOXMES_MTPROTO_DISABLED", "This action is not available.");
        messages.put("INTERNAL_ERROR", "Unable to complete this action. Please try again later.");
        messages.put("INTERNAL_SERVER_ERROR", "Unable to complete this action. Please try again later.");
        messages.put("FOXMES_INTERNAL", "Unable to complete this action. Please try again later.");
        MESSAGES = Collections.unmodifiableMap(messages);
    }

    private FoxMesErrorText() {
    }

    public static String message(String code, int status) {
        String key = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        String message = MESSAGES.get(key);
        if (message != null) return message;
        if (key.startsWith("FLOOD_WAIT_") || key.startsWith("FLOOD_PREMIUM_WAIT_")) {
            return "Too many attempts. Please wait and try again.";
        }
        switch (status) {
            case 401: return "Your session has ended. Please sign in again.";
            case 403: return "You do not have permission to perform this action.";
            case 404: return "The requested item is no longer available.";
            case 409: return "This item has changed. Reload it and try again.";
            case 413: return "This file is too large. Please choose a smaller file.";
            case 429: return "Too many attempts. Please wait and try again.";
            default: return status >= 500 ? "Unable to complete this action. Please try again later." : "Unable to complete this action. Please try again.";
        }
    }

    public static String alertText(String text) {
        String[] lines = text.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            String key = trimmed.toUpperCase(Locale.ROOT);
            if (MESSAGES.containsKey(key) || key.startsWith("FLOOD_WAIT_") || key.startsWith("FLOOD_PREMIUM_WAIT_")
                    || TOKEN.matcher(trimmed).matches()) {
                lines[i] = message(lines[i], 0);
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : text;
    }
}
