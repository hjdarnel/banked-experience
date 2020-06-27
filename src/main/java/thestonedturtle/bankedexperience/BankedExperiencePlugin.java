package thestonedturtle.bankedexperience;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import thestonedturtle.bankedexperience.data.Activity;
import thestonedturtle.bankedexperience.data.ExperienceItem;

@Slf4j
@PluginDescriptor(
	name = "Banked Experience"
)
public class BankedExperiencePlugin extends Plugin
{
	private static final BufferedImage ICON = ImageUtil.getResourceStreamFromClass(BankedExperiencePlugin.class, "banked.png");
	private static final Map<Integer, Integer> EMPTY_MAP = new HashMap<>();
	private static final String CONFIG_GROUP = "bankedexperience";
	private static final String VAULT_CONFIG_KEY = "grabFromSeedVault";
	private static final String INVENTORY_CONFIG_KEY = "grabFromInventory";
	private static final String LOOTING_BAG_CONFIG_KEY = "grabFromLootingBag";
	private static final int LOOTING_BAG_ID = 516;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private BankedExperienceConfig config;

	@Provides
	BankedExperienceConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankedExperienceConfig.class);
	}

	private NavigationButton navButton;
	private BankedCalculatorPanel panel;
	private boolean prepared = false;
	private int bankHash = -1;
	private int vaultHash = -1;
	private int inventoryHash = -1;
	private int lootingBagHash = -1;
	private int lastCheckTick = -1;

	@Override
	protected void startUp() throws Exception
	{
		panel = new BankedCalculatorPanel(client, config, skillIconManager, itemManager);
		navButton = NavigationButton.builder()
			.tooltip("Banked XP")
			.icon(ICON)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		if (!prepared)
		{
			clientThread.invoke(() ->
			{
				switch (client.getGameState())
				{
					case LOGIN_SCREEN:
					case LOGIN_SCREEN_AUTHENTICATOR:
					case LOGGING_IN:
					case LOADING:
					case LOGGED_IN:
					case CONNECTION_LOST:
					case HOPPING:
						ExperienceItem.prepareItemCompositions(itemManager);
						Activity.prepareItemCompositions(itemManager);
						prepared = true;
						return true;
					default:
						return false;
				}
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		clientToolbar.removeNavigation(navButton);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(CONFIG_GROUP))
		{
			return;
		}

		switch (event.getKey())
		{
			case VAULT_CONFIG_KEY:
				vaultHash = -1;
				SwingUtilities.invokeLater(() -> panel.setVaultMap(EMPTY_MAP));
				break;
			case INVENTORY_CONFIG_KEY:
				inventoryHash = -1;
				SwingUtilities.invokeLater(() -> panel.setInventoryMap(EMPTY_MAP));
				break;
			case LOOTING_BAG_CONFIG_KEY:
				lootingBagHash = -1;
				SwingUtilities.invokeLater(() -> panel.setLootingBagMap(EMPTY_MAP));
				break;
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!event.getEventName().equals("setBankTitle") || client.getTickCount() == lastCheckTick)
		{
			return;
		}

		final Map<Integer, Integer> m = getItemsFromInventory(InventoryID.BANK);
		if (m == null)
		{
			return;
		}

		final int curHash = m.hashCode();
		if (bankHash != curHash)
		{
			bankHash = curHash;
			SwingUtilities.invokeLater(() -> panel.setBankMap(m));
		}

		lastCheckTick = client.getTickCount();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged ev)
	{
		if (ev.getContainerId() == InventoryID.SEED_VAULT.getId() && config.grabFromSeedVault())
		{
			final Map<Integer, Integer> m = getItemsFromItemContainer(ev.getItemContainer());
			if (m == null)
			{
				return;
			}

			final int curHash = m.hashCode();
			if (vaultHash != curHash)
			{
				vaultHash = curHash;
				SwingUtilities.invokeLater(() -> panel.setVaultMap(m));
			}
		}
		else if (ev.getContainerId() == InventoryID.INVENTORY.getId() && config.grabFromInventory())
		{
			final Map<Integer, Integer> m = getItemsFromItemContainer(ev.getItemContainer());
			if (m == null)
			{
				return;
			}

			final int curHash = m.hashCode();
			if (inventoryHash != curHash)
			{
				inventoryHash = curHash;
				SwingUtilities.invokeLater(() -> panel.setInventoryMap(m));
			}
		}
		else if (ev.getContainerId() == LOOTING_BAG_ID && config.grabFromLootingBag())
		{
			final Map<Integer, Integer> m = getItemsFromInventory(InventoryID.SEED_VAULT);
			if (m == null)
			{
				return;
			}

			final int curHash = m.hashCode();
			if (lootingBagHash != curHash)
			{
				lootingBagHash = curHash;
				SwingUtilities.invokeLater(() -> panel.setLootingBagMap(m));
			}
		}
	}

	@Nullable
	private Map<Integer, Integer> getItemsFromInventory(InventoryID inventoryID)
	{
		return getItemsFromItemContainer(client.getItemContainer(inventoryID));
	}

	@Nullable
	private Map<Integer, Integer> getItemsFromItemContainer(final ItemContainer c)
	{
		// Check if the contents have changed.
		if (c == null)
		{
			return null;
		}

		final Map<Integer, Integer> m = new HashMap<>();
		for (Item widgetItem : c.getItems())
		{
			m.put(widgetItem.getId(), widgetItem.getQuantity());
		}

		return m;
	}
}
