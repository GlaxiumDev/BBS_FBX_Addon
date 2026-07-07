package elgatopro300.bbsfbx;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterL10nEvent;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BBS FS addon entrypoint ({@code bbs-addon} in fabric.mod.json).
 *
 * <p>BBS FS doesn't have a {@code RegisterModelLoadersEvent} like CML does,
 * so the FBX model loader is registered through a mixin instead (see
 * {@code elgatopro300.bbsfbx.mixin.ModelManagerMixin}). This class only
 * handles what FS's event bus supports: L10n registration.</p>
 */
public class BBSFbxAddon implements BBSAddonMod
{
    public static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");

    public BBSFbxAddon()
    {
        LOGGER.info("BBS Fbx Addon ready");
    }

    @Subscribe
    public void registerL10n(RegisterL10nEvent event)
    {
        event.l10n.register((lang) -> List.of(
            new Link("bbs_fbx", "lang/" + L10n.DEFAULT_LANGUAGE + ".json"),
            new Link("bbs_fbx", "lang/" + lang + ".json")
        ));

        try
        {
            event.l10n.reload();
        }
        catch (Exception e)
        {
            LOGGER.warn("Failed to reload BBS L10n", e);
        }
    }
}
