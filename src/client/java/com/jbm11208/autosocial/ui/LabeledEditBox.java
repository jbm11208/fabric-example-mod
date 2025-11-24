package com.jbm11208.autosocial.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class LabeledEditBox extends EditBox {
    private final Component label;
    private final Font myFont;

    public LabeledEditBox(Font font, int x, int y, int width, int height,
                          Component label, Component placeholder) {
        super(font, x, y, width, height, placeholder);
        this.myFont = font;
        this.label = label;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        // Draw the base EditBox first
        super.renderWidget(guiGraphics, mouseX, mouseY, delta);

        if (myFont == null || label == null)
            return;

        // Calculate vertical center for the label
        int textY = this.getY() + (this.getHeight() - myFont.lineHeight) / 2;

        // Calculate label width
        int labelWidth = myFont.width(label);

        // Position label to the left of the edit box
        int textX = this.getX() - labelWidth - 10; // Increased spacing to 10 pixels

        // Ensure the label doesn't go off the left edge of the screen
        textX = Math.max(textX, 4); // Increased minimum spacing from left edge

        // Debug: Print position info (remove after testing)
        // System.out.println("Label: " + label.getString() + " at (" + textX + ", " + textY + ")");

        // Draw the label with better color and shadow for visibility
        // Slightly grayish white for better visibility
        int labelColor = 0xFFE0E0E0;
        guiGraphics.drawString(
                myFont,
                label,
                textX,
                textY,
                labelColor,
                false // no shadow
        );
    }
}