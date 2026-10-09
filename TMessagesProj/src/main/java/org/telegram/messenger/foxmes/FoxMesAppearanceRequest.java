package org.telegram.messenger.foxmes;

import org.telegram.tgnet.TLObject;
import java.util.Objects;

/** A bridge-only request containing the complete appearance editor draft. */
public final class FoxMesAppearanceRequest extends TLObject {
    private final Integer nameColor, profileColor;
    private final Long nameIcon, profileIcon;
    private final String operationId = FoxMesJson.operationId();

    public FoxMesAppearanceRequest(int nameColor, long nameIcon, int profileColor, long profileIcon) {
        this.nameColor = nameColor < 0 ? null : nameColor;
        this.nameIcon = nameIcon == 0 ? null : nameIcon;
        this.profileColor = profileColor < 0 ? null : profileColor;
        this.profileIcon = profileIcon == 0 ? null : profileIcon;
    }

    FoxMesJson.Body body() {
        return FoxMesJson.body().put("operation_id", operationId)
                .put("name", choice(nameColor, nameIcon)).put("profile", choice(profileColor, profileIcon));
    }

    private static FoxMesJson.Body choice(Integer color, Long icon) {
        return FoxMesJson.body().put("color_id", color).put("background_emoji_id", icon);
    }

    boolean matches(FoxMesModels.PeerAppearance appearance) {
        return appearance != null && appearance.revision > 0
                && Objects.equals(nameColor, appearance.nameColorId)
                && Objects.equals(nameIcon, normalize(appearance.backgroundEmojiId))
                && Objects.equals(profileColor, appearance.profileColorId)
                && Objects.equals(profileIcon, normalize(appearance.profileBackgroundEmojiId));
    }

    private static Long normalize(Long icon) { return icon != null && icon == 0 ? null : icon; }
}
