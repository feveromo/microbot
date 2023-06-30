package net.runelite.client.plugins.microbot;

import net.runelite.client.plugins.microbot.cooking.CookingScript;
import net.runelite.client.plugins.microbot.util.walker.PathTileOverlay;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class MicrobotOverlay extends OverlayPanel {
    MicrobotPlugin plugin;
    @Inject
    MicrobotOverlay(MicrobotPlugin plugin)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        this.plugin = plugin;
    }
    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            PathTileOverlay.render(graphics);

            if (plugin.cookingScript != null) {
                drawCookingOverlay();
            }

        } catch(Exception ex) {
            System.out.println(ex.getMessage());
        }
        return super.render(graphics);
    }

    private void drawCookingOverlay() {
        panelComponent.setPreferredSize(new Dimension(200, 300));
        panelComponent.getChildren().add(TitleComponent.builder()
                .text("Micro Example V" + CookingScript.version)
                .color(Color.GREEN)
                .build());

        panelComponent.getChildren().add(LineComponent.builder().build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left(Microbot.status)
                .build());
    }
}

