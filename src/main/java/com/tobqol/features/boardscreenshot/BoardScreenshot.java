/*
 * Copyright (c) 2025, contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.tobqol.features.boardscreenshot;

import com.tobqol.TheatreQOLConfig;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@Singleton
public class BoardScreenshot
{
	// Negative IDs are used for custom sprite overrides — chosen to not conflict with TOA (-420/-421).
	private static final int CAMERA_SPRITE_IDX = -422;
	private static final int CAMERA_HOVER_SPRITE_IDX = -423;

	// OSRS sprite ID for the chatbox report (camera) icon.
	// If the button renders blank, verify this against net.runelite.api.SpriteID.CHATBOX_REPORT_BUTTON.
	private static final int CHATBOX_REPORT_SPRITE_ID = 159;

	@Inject
	private Client client;

	@Inject
	private TheatreQOLConfig config;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private EventBus eventBus;

	private Widget button = null;

	public void startUp()
	{
		eventBus.register(this);
		clientThread.invokeLater(this::createButton);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		clientThread.invokeLater(() ->
		{
			removeCameraIconOverride();
			button = null;
		});
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int configGroup = config.boardScreenshotWidgetGroup();
		if (configGroup > 0 && event.getGroupId() == configGroup)
		{
			clientThread.invokeLater(this::createButton);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!TheatreQOLConfig.GROUP_NAME.equals(event.getGroup()))
		{
			return;
		}

		switch (event.getKey())
		{
			case "boardScreenshotEnable":
			case "boardScreenshotWhiteIcon":
			case "boardScreenshotWidgetGroup":
			case "boardScreenshotWidgetChild":
				clientThread.invokeLater(() ->
				{
					removeCameraIconOverride();
					button = null;
					createButton();
				});
				break;
		}
	}

	private void createButton()
	{
		if (!config.boardScreenshotEnable())
		{
			return;
		}

		int group = config.boardScreenshotWidgetGroup();
		if (group == 0)
		{
			return;
		}

		Widget parent = client.getWidget(group, config.boardScreenshotWidgetChild());
		if (parent == null)
		{
			return;
		}

		// Guard against re-adding the button when the script fires again after widget creation.
		Widget[] existing = parent.getDynamicChildren();
		if (existing != null)
		{
			for (Widget child : existing)
			{
				if (child.equals(button))
				{
					return;
				}
			}
		}

		boolean hasCameraSprite = addCameraIconOverride();

		button = parent.createChild(-1, hasCameraSprite ? WidgetType.GRAPHIC : WidgetType.TEXT);
		button.setOriginalHeight(20);
		button.setOriginalWidth(hasCameraSprite ? 20 : 30);
		button.setOriginalX(Math.max(0, parent.getWidth() - 24));
		button.setOriginalY(4);

		if (hasCameraSprite)
		{
			button.setSpriteId(CAMERA_SPRITE_IDX);
		}
		else
		{
			button.setText("[ss]");
			button.setTextColor(0xFFFFFF);
			button.setFontId(496); // RuneScape small
		}

		button.setHasListener(true);
		button.setAction(0, "Screenshot Board");
		button.setAction(1, "Copy to clipboard");
		button.setOnOpListener((JavaScriptCallback) e ->
		{
			// op=1 is left-click / first menu entry; op=2 is "Copy to clipboard".
			if (e.getOp() == 1)
			{
				clientThread.invokeLater(() -> screenshot(false));
			}
			else if (e.getOp() == 2)
			{
				clientThread.invokeLater(() -> screenshot(true));
			}
		});

		if (hasCameraSprite)
		{
			button.setOnMouseOverListener((JavaScriptCallback) e -> button.setSpriteId(CAMERA_HOVER_SPRITE_IDX));
			button.setOnMouseLeaveListener((JavaScriptCallback) e -> button.setSpriteId(CAMERA_SPRITE_IDX));
		}

		button.revalidate();
	}

	/**
	 * Loads the camera sprite from the OSRS sprite index and registers it as an override.
	 * Returns {@code true} when the override was registered successfully.
	 */
	private boolean addCameraIconOverride()
	{
		client.getWidgetSpriteCache().reset();

		if (client.getIndexSprites() == null)
		{
			return false;
		}

		SpritePixels[] baseSprites;
		try
		{
			baseSprites = client.getSprites(client.getIndexSprites(), CHATBOX_REPORT_SPRITE_ID, 0);
		}
		catch (Exception e)
		{
			log.warn("Failed to load camera sprite for board screenshot button", e);
			return false;
		}

		if (baseSprites == null || baseSprites[0] == null)
		{
			return false;
		}

		BufferedImage cameraImg = baseSprites[0].toBufferedImage();
		if (config.boardScreenshotWhiteIcon())
		{
			cameraImg = ImageUtil.recolorImage(cameraImg, Color.WHITE);
		}

		client.getSpriteOverrides().put(CAMERA_SPRITE_IDX,
			ImageUtil.getImageSpritePixels(cameraImg, client));
		// Slightly transparent for the hover state.
		client.getSpriteOverrides().put(CAMERA_HOVER_SPRITE_IDX,
			ImageUtil.getImageSpritePixels(ImageUtil.alphaOffset(cameraImg, 0.7f), client));

		return true;
	}

	private void removeCameraIconOverride()
	{
		client.getWidgetSpriteCache().reset();
		client.getSpriteOverrides().remove(CAMERA_SPRITE_IDX);
		client.getSpriteOverrides().remove(CAMERA_HOVER_SPRITE_IDX);
	}

	// -------------------------------------------------------------------------
	// Screenshot capture
	// -------------------------------------------------------------------------

	private void screenshot(boolean clipboardOnly)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		client.getWidgetSpriteCache().reset();

		int group = config.boardScreenshotWidgetGroup();
		if (group == 0)
		{
			return;
		}

		Widget widget = client.getWidget(group, config.boardScreenshotWidgetChild());
		if (widget == null || widget.isHidden())
		{
			return;
		}

		int width = widget.getWidth();
		int height = widget.getHeight();
		if (width <= 0 || height <= 0)
		{
			return;
		}

		BufferedImage capture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = capture.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		drawWidgetTree(g, widget, 0, 0);

		g.dispose();

		BufferedImage out = toRGB(capture);

		if (clipboardOnly)
		{
			Toolkit.getDefaultToolkit().getSystemClipboard()
				.setContents(new TransferableImage(out), null);
		}
		else
		{
			imageCapture.saveScreenshot(out, "tob-board", "tob-board", true, false);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "TOB board screenshot saved.", null);
		}
	}

	// -------------------------------------------------------------------------
	// Widget rendering
	// -------------------------------------------------------------------------

	private void drawWidgetTree(Graphics g, Widget widget, int x, int y)
	{
		if (widget == null || widget.isHidden())
		{
			return;
		}

		drawWidgetContent(g, widget, x, y);

		Widget[] staticChildren = widget.getStaticChildren();
		Widget[] dynamicChildren = widget.getDynamicChildren();

		if (staticChildren != null)
		{
			for (Widget child : staticChildren)
			{
				if (child == null || child.isHidden())
				{
					continue;
				}
				drawWidgetTree(g, child, x + child.getRelativeX(), y + child.getRelativeY());
			}
		}

		if (dynamicChildren != null)
		{
			for (Widget child : dynamicChildren)
			{
				if (child == null || child.isHidden() || child.equals(button))
				{
					continue; // skip the camera button itself
				}
				drawWidgetTree(g, child, x + child.getRelativeX(), y + child.getRelativeY());
			}
		}
	}

	private void drawWidgetContent(Graphics g, Widget widget, int x, int y)
	{
		int type = widget.getType();
		if (type == 0) // WidgetType.LAYER — container, no content to draw directly
		{
			return;
		}

		int w = widget.getWidth();
		int h = widget.getHeight();

		if (widget.getSpriteId() > 0)
		{
			drawSprite(g, widget, x, y, w, h);
		}
		else if (type == WidgetType.TEXT)
		{
			drawText(g, widget, x, y, w, h);
		}
		else if (type == WidgetType.RECTANGLE)
		{
			drawRectangle(g, widget, x, y, w, h);
		}
	}

	private void drawSprite(Graphics g, Widget widget, int x, int y, int w, int h)
	{
		if (client.getIndexSprites() == null)
		{
			return;
		}

		SpritePixels[] sp;
		try
		{
			sp = client.getSprites(client.getIndexSprites(), widget.getSpriteId(), 0);
		}
		catch (Exception e)
		{
			return;
		}

		if (sp == null || sp[0] == null)
		{
			return;
		}

		BufferedImage img = sp[0].toBufferedImage();

		if (widget.getSpriteTiling())
		{
			for (int dx = x; dx < x + w; dx += img.getWidth())
			{
				for (int dy = y; dy < y + h; dy += img.getHeight())
				{
					g.drawImage(img, dx, dy, null);
				}
			}
		}
		else
		{
			g.drawImage(img, x, y, w, h, null);
		}
	}

	private void drawText(Graphics g, Widget widget, int x, int y, int w, int h)
	{
		String text = Text.removeTags(widget.getText());
		if (text == null || text.isEmpty())
		{
			return;
		}

		Font font = FontManager.getRunescapeSmallFont();
		Graphics textG = g.create(x, y, w, h);
		textG.setFont(font);

		int xPos = 0;
		int yPos = font.getSize() - 3;

		int textWidth = textG.getFontMetrics().stringWidth(text);
		int xAlign = widget.getXTextAlignment();
		int yAlign = widget.getYTextAlignment();

		if (xAlign == 1) // centered
		{
			xPos = (w - textWidth) / 2 + 1;
		}
		else if (xAlign == 2) // right
		{
			xPos = w - textWidth;
		}

		if (yAlign == 1) // centered
		{
			yPos = (h + font.getSize()) / 2 - 1;
		}
		else if (yAlign == 2) // bottom
		{
			yPos = h;
		}

		if (widget.getTextShadowed())
		{
			textG.setColor(Color.BLACK);
			textG.drawString(text, xPos + 1, yPos + 1);
		}

		textG.setColor(new Color(widget.getTextColor()));
		textG.drawString(text, xPos, yPos);
		textG.dispose();
	}

	private void drawRectangle(Graphics g, Widget widget, int x, int y, int w, int h)
	{
		Color c = new Color(widget.getTextColor());
		int opacity = widget.getOpacity();
		if (opacity > 0)
		{
			c = new Color(c.getRed(), c.getGreen(), c.getBlue(), opacity);
		}
		g.setColor(c);
		g.drawRect(x, y, w - 1, h - 1);
	}

	private static BufferedImage toRGB(BufferedImage image)
	{
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setComposite(AlphaComposite.Src);
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return out;
	}
}
