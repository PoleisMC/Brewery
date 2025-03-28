package com.dre.brewery.filedata;

import com.dre.brewery.*;
import com.dre.brewery.lore.Base91DecoderStream;
import com.dre.brewery.recipe.Ingredient;
import com.dre.brewery.recipe.SimpleItem;
import com.dre.brewery.utility.BUtil;
import com.dre.brewery.utility.BoundingBox;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class BData {

	public static AtomicInteger dataMutex = new AtomicInteger(0); // WorldData: -1 = Saving, 0 = Free, >= 1 = Loading
	public static FileConfiguration worldData = null; // World Data Cache for consecutive loading of Worlds. Nulled after a data save


	// load all Data
	public static void readData() {
		File file = new File(Brewery.getInstance().getDataFolder(), "data.yml");
		if (file.exists()) {
			long t1 = System.currentTimeMillis();

			FileConfiguration data = YamlConfiguration.loadConfiguration(file);

			long t2 = System.currentTimeMillis();

			Brewery.getInstance().debugLog("Loading data.yml: " + (t2 - t1) + "ms");

			// Check if data is the newest version
			String version = data.getString("Version", null);
			if (version != null) {
				if (!version.equals(DataSave.dataVersion)) {
					Brewery.getInstance().log("Data File is being updated...");
					File worldFile = new File(Brewery.getInstance().getDataFolder(), "worlddata.yml");
					new DataUpdater(data, file, worldFile).update(version);
					data = YamlConfiguration.loadConfiguration(file);
					Brewery.getInstance().log("Data Updated to version: " + DataSave.dataVersion);
				}
			}

			Brew.installTime = data.getLong("installTime", System.currentTimeMillis());
			MCBarrel.mcBarrelTime = data.getLong("MCBarrelTime", 0);

			Brew.loadPrevSeeds(data);

			List<Integer> brewsCreated = data.getIntegerList("brewsCreated");
			if (brewsCreated != null && brewsCreated.size() == 7) {
				int hash = data.getInt("brewsCreatedH");
				// Check the hash to prevent tampering with statistics
				if (brewsCreated.hashCode() == hash) {
					Brewery.getInstance().stats.brewsCreated = brewsCreated.get(0);
					Brewery.getInstance().stats.brewsCreatedCmd = brewsCreated.get(1);
					Brewery.getInstance().stats.exc = brewsCreated.get(2);
					Brewery.getInstance().stats.good = brewsCreated.get(3);
					Brewery.getInstance().stats.norm = brewsCreated.get(4);
					Brewery.getInstance().stats.bad = brewsCreated.get(5);
					Brewery.getInstance().stats.terr = brewsCreated.get(6);
				}
			}

			// loading Ingredients into ingMap
			// Only for Legacy Brews
			Map<String, BIngredients> ingMap = new HashMap<>();
			ConfigurationSection section = data.getConfigurationSection("Ingredients");
			if (section != null) {
				for (String id : section.getKeys(false)) {
					if (section.isConfigurationSection(id + ".mats")) {
						// Old way of saving
						ConfigurationSection matSection = section.getConfigurationSection(id + ".mats");
						if (matSection != null) {
							// matSection has all the materials + amount as Integers
							List<Ingredient> ingredients = oldDeserializeIngredients(matSection);
							ingMap.put(id, new BIngredients(ingredients, section.getInt(id + ".cookedTime", 0), true));
						} else {
							Brewery.getInstance().errorLog("Ingredient id: '" + id + "' incomplete in data.yml");
						}
					} else {
						// New way of saving ingredients
						ingMap.put(id, deserializeIngredients(section.getString(id + ".mats")));
					}
				}
			}

			// loading Brew legacy
			section = data.getConfigurationSection("Brew");
			if (section != null) {
				// All sections have the UID as name
				for (String uid : section.getKeys(false)) {
					BIngredients ingredients = getIngredients(ingMap, section.getString(uid + ".ingId"));
					int quality = section.getInt(uid + ".quality", 0);
					int alc = section.getInt(uid + ".alc", 0);
					byte distillRuns = (byte) section.getInt(uid + ".distillRuns", 0);
					float ageTime = (float) section.getDouble(uid + ".ageTime", 0.0);
					float wood = (float) section.getDouble(uid + ".wood", -1.0);
					String recipe = section.getString(uid + ".recipe", null);
					boolean unlabeled = section.getBoolean(uid + ".unlabeled", false);
					boolean persistent = section.getBoolean(uid + ".persist", false);
					boolean stat = section.getBoolean(uid + ".stat", false);
					int lastUpdate = section.getInt(uid + ".lastUpdate", 0);

					Brew.loadLegacy(ingredients, Brewery.getInstance().parseInt(uid), quality, alc, distillRuns, ageTime, wood, recipe, unlabeled, persistent, stat, lastUpdate);
				}
			}

			// Store how many legacy brews were created
			if (Brewery.getInstance().stats.brewsCreated <= 0) {
				Brewery.getInstance().stats.brewsCreated = 0;
				Brewery.getInstance().stats.brewsCreatedCmd = 0;
				Brewery.getInstance().stats.exc = 0;
				Brewery.getInstance().stats.good = 0;
				Brewery.getInstance().stats.norm = 0;
				Brewery.getInstance().stats.bad = 0;
				Brewery.getInstance().stats.terr = 0;
				if (!Brew.noLegacy()) {
					for (int i = Brew.legacyPotions.size(); i > 0; i--) {
						Brewery.getInstance().stats.metricsForCreate(false);
					}
				}
			}

			// Remove Legacy Potions that haven't been touched in a long time, these may have been lost
			if (!Brew.noLegacy()) {
				int currentHoursAfterInstall = (int) ((double) (System.currentTimeMillis() - Brew.installTime) / 3600000D);
				int purgeTime = currentHoursAfterInstall - (24 * 30 * 4); // Purge Time is 4 Months ago
				if (purgeTime > 0) {
					int removed = 0;
					for (Iterator<Brew> iterator = Brew.legacyPotions.values().iterator(); iterator.hasNext(); ) {
						Brew brew = iterator.next();
						if (brew.getLastUpdate() < purgeTime) {
							iterator.remove();
							removed++;
						}
					}
					if (removed > 0) {
						Brewery.getInstance().log("Removed " + removed + " Legacy Brews older than 3 months");
					}
				}
			}

			// loading BPlayer
			section = data.getConfigurationSection("Player");
			if (section != null) {
				// keys have players uuid
				for (String uuid : section.getKeys(false)) {
					try {
						//noinspection ResultOfMethodCallIgnored
						UUID.fromString(uuid);
						if (!Brewery.getInstance().useUUID) {
							continue;
						}
					} catch (IllegalArgumentException e) {
						if (Brewery.getInstance().useUUID) {
							continue;
						}
					}

					int quality = section.getInt(uuid + ".quality");
					int drunk = section.getInt(uuid + ".drunk");
					int offDrunk = section.getInt(uuid + ".offDrunk", 0);

					new BPlayer(uuid, quality, drunk, offDrunk);
				}
			}


			final List<World> worlds = Brewery.getInstance().getServer().getWorlds();
			if (BConfig.loadDataAsync) {
				Brewery.getInstance().getServer().getScheduler().runTaskAsynchronously(Brewery.getInstance(), () -> lwDataTask(worlds));
			} else {
				lwDataTask(worlds);
			}

		} else {
			Brewery.getInstance().log("No data.yml found, will create new one!");
		}
	}

	public static BIngredients deserializeIngredients(String mat) {
		try (DataInputStream in = new DataInputStream(new Base91DecoderStream(new ByteArrayInputStream(mat.getBytes())))) {
			byte ver = in.readByte();
			return BIngredients.load(in, ver);
		} catch (IOException e) {
			Brewery.getInstance().getLogger().log(Level.SEVERE, e.getMessage(), e);
			return new BIngredients();
		}
	}

	// Loading from the old way of saving ingredients
	public static List<Ingredient> oldDeserializeIngredients(ConfigurationSection matSection) {
		List<Ingredient> ingredients = new ArrayList<>();
		for (String mat : matSection.getKeys(false)) {
			String[] matSplit = mat.split(",");
			Material m = Material.getMaterial(matSplit[0]);
			if (m == null && Brewery.getInstance().use1_13) {
				if (matSplit[0].equals("LONG_GRASS")) {
					m = Material.GRASS;
				} else {
					m = Material.matchMaterial(matSplit[0], true);
				}
				Brewery.getInstance().debugLog("converting Data Material from " + matSplit[0] + " to " + m);
			}
			if (m == null) continue;
			SimpleItem item;
			if (matSplit.length == 2) {
				item = new SimpleItem(m, (short) Brewery.getInstance().parseInt(matSplit[1]));
			} else {
				item = new SimpleItem(m);
			}
			item.setAmount(matSection.getInt(mat));
			ingredients.add(item);
		}
		return ingredients;
	}

	// returns Ingredients by id from the specified ingMap
	public static BIngredients getIngredients(Map<String, BIngredients> ingMap, String id) {
		if (!ingMap.isEmpty()) {
			if (ingMap.containsKey(id)) {
				return ingMap.get(id);
			}
		}
		Brewery.getInstance().errorLog("Ingredient id: '" + id + "' not found in data.yml");
		return new BIngredients();
	}

	// loads BIngredients from an ingredient section
	public static BIngredients loadCauldronIng(ConfigurationSection section, String path) {
		if (section.isConfigurationSection(path)) {
			// Old way of saving
			ConfigurationSection matSection = section.getConfigurationSection(path);
			if (matSection != null) {
				// matSection has all the materials + amount as Integers
				return new BIngredients(oldDeserializeIngredients(section), 0);
			} else {
				Brewery.getInstance().errorLog("Cauldron is missing Ingredient Section");
				return new BIngredients();
			}
		} else {
			// New way of saving ingredients
			return deserializeIngredients(section.getString(path));
		}
	}

	public static void lwDataTask(List<World> worlds) {
		if (!acquireDataLoadMutex()) return; // Tries for 60 sec

		try {
			for (World world : worlds) {
				if (world.getName().startsWith("DXL_")) {
					loadWorldData(BUtil.getDxlName(world.getName()), world);
				} else {
					loadWorldData(world.getUID().toString(), world);
				}
			}
		} catch (Exception e) {
			Brewery.getInstance().getLogger().log(Level.SEVERE, e.getMessage(), e);
		} finally {
			releaseDataLoadMutex();
			if (BConfig.loadDataAsync && BData.dataMutex.get() == 0) {
				Brewery.getInstance().log("Background data loading complete.");
			}
		}
	}

	// load Block locations of given world
	// can be run async
	public static void loadWorldData(String uuid, World world) {
		if (BData.worldData == null) {
			File file = new File(Brewery.getInstance().getDataFolder(), "worlddata.yml");
			if (file.exists()) {
				long t1 = System.currentTimeMillis();
				BData.worldData = YamlConfiguration.loadConfiguration(file);
				long t2 = System.currentTimeMillis();
				if (t2 - t1 > 15000) {
					// Spigot is _very_ slow at loading inventories from yml. Paper is way faster.
					// Notify Admin that loading Data took long (its async so not much of a problem)
					Brewery.getInstance().log("Bukkit took " + (t2 - t1) / 1000.0 + "s to load Inventories from the World-Data File (in the Background),");
					Brewery.getInstance().log("consider switching to Paper, or have less items in Barrels if it takes a long time for Barrels to become available");
				} else {
					Brewery.getInstance().debugLog("Loading worlddata.yml: " + (t2 - t1) + "ms");
				}
			} else {
				return;
			}
		}

		// loading BCauldron
		final Map<Block, BCauldron> initCauldrons = new HashMap<>();
		if (BData.worldData.contains("BCauldron." + uuid)) {
			ConfigurationSection section = BData.worldData.getConfigurationSection("BCauldron." + uuid);
			for (String cauldron : section.getKeys(false)) {
				// block is splitted into x/y/z
				String block = section.getString(cauldron + ".block");
				if (block != null) {
					String[] splitted = block.split("/");
					if (splitted.length == 3) {

						Block worldBlock = world.getBlockAt(Brewery.getInstance().parseInt(splitted[0]), Brewery.getInstance().parseInt(splitted[1]), Brewery.getInstance().parseInt(splitted[2]));
						BIngredients ingredients = loadCauldronIng(section, cauldron + ".ingredients");
						int state = section.getInt(cauldron + ".state", 0);

						initCauldrons.put(worldBlock, new BCauldron(worldBlock, ingredients, state));
					} else {
						Brewery.getInstance().errorLog("Incomplete Block-Data in data.yml: " + section.getCurrentPath() + "." + cauldron);
					}
				} else {
					Brewery.getInstance().errorLog("Missing Block-Data in data.yml: " + section.getCurrentPath() + "." + cauldron);
				}
			}
		}

		// loading Barrel
		final List<Barrel> initBarrels = new ArrayList<>();
		final List<Barrel> initBadBarrels = new ArrayList<>();
		if (BData.worldData.contains("Barrel." + uuid)) {
			ConfigurationSection section = BData.worldData.getConfigurationSection("Barrel." + uuid);
			for (String barrel : section.getKeys(false)) {
				// block spigot is splitted into x/y/z
				String spigot = section.getString(barrel + ".spigot");
				if (spigot != null) {
					String[] splitted = spigot.split("/");
					if (splitted.length == 3) {

						// load itemStacks from invSection
						ConfigurationSection invSection = section.getConfigurationSection(barrel + ".inv");
						Block block = world.getBlockAt(Brewery.getInstance().parseInt(splitted[0]), Brewery.getInstance().parseInt(splitted[1]), Brewery.getInstance().parseInt(splitted[2]));
						float time = (float) section.getDouble(barrel + ".time", 0.0);
						byte sign = (byte) section.getInt(barrel + ".sign", 0);

						BoundingBox box = null;
						if (section.contains(barrel + ".bounds")) {
							String[] bds = section.getString(barrel + ".bounds", "").split(",");
							if (bds.length == 6) {
								box = new BoundingBox(Brewery.getInstance().parseInt(bds[0]), Brewery.getInstance().parseInt(bds[1]), Brewery.getInstance().parseInt(bds[2]), Brewery.getInstance().parseInt(bds[3]), Brewery.getInstance().parseInt(bds[4]), Brewery.getInstance().parseInt(bds[5]));
							}
						} else if (section.contains(barrel + ".st")) {
							// Convert from Stair and Wood Locations to BoundingBox
							String[] st = section.getString(barrel + ".st", "").split(",");
							String[] wo = section.getString(barrel + ".wo", "").split(",");
							int woLength = wo.length;
							if (woLength <= 1) {
								woLength = 0;
							}
							String[] points = new String[st.length + woLength];
							System.arraycopy(st, 0, points, 0, st.length);
							if (woLength > 1) {
								System.arraycopy(wo, 0, points, st.length, woLength);
							}
							int[] locs = Arrays.stream(points).mapToInt(s -> Brewery.getInstance().parseInt(s)).toArray();
							try {
								box = BoundingBox.fromPoints(locs);
							} catch (Exception e) {
								Brewery.getInstance().getLogger().log(Level.SEVERE, e.getMessage(), e);
							}
						}

						Barrel b;
						if (invSection != null) {
							b = new Barrel(block, sign, box, invSection.getValues(true), time, true);
						} else {
							// Barrel has no inventory
							b = new Barrel(block, sign, box, null, time, true);
						}

						if (b.getBody().getBounds() != null) {
							initBarrels.add(b);
						} else {
							// The Barrel Bounds need recreating, as they were missing or corrupt
							initBadBarrels.add(b);
						}

					} else {
						Brewery.getInstance().errorLog("Incomplete Block-Data in data.yml: " + section.getCurrentPath() + "." + barrel);
					}
				} else {
					Brewery.getInstance().errorLog("Missing Block-Data in data.yml: " + section.getCurrentPath() + "." + barrel);
				}
			}
		}

		// loading Wakeup
		final List<Wakeup> initWakeups = new ArrayList<>();
		if (BData.worldData.contains("Wakeup." + uuid)) {
			ConfigurationSection section = BData.worldData.getConfigurationSection("Wakeup." + uuid);
			for (String wakeup : section.getKeys(false)) {
				// loc of wakeup is splitted into x/y/z/pitch/yaw
				String loc = section.getString(wakeup);
				if (loc != null) {
					String[] splitted = loc.split("/");
					if (splitted.length == 5) {

						double x = Brewery.getInstance().parseDouble(splitted[0]);
						double y = Brewery.getInstance().parseDouble(splitted[1]);
						double z = Brewery.getInstance().parseDouble(splitted[2]);
						float pitch = Brewery.getInstance().parseFloat(splitted[3]);
						float yaw = Brewery.getInstance().parseFloat(splitted[4]);
						Location location = new Location(world, x, y, z, yaw, pitch);

						initWakeups.add(new Wakeup(location));

					} else {
						Brewery.getInstance().errorLog("Incomplete Location-Data in data.yml: " + section.getCurrentPath() + "." + wakeup);
					}
				}
			}
		}

		// Merge Loaded Data in Main Thread
		Brewery.getInstance().getServer().getScheduler().runTask(Brewery.getInstance(), () -> {
			if (Brewery.getInstance().getServer().getWorld(world.getUID()) == null) {
				return;
			}
			if (!initCauldrons.isEmpty()) {
				BCauldron.bcauldrons.putAll(initCauldrons);
			}
			if (!initBarrels.isEmpty()) {
				Barrel.barrels.addAll(initBarrels);
			}
			if (!initBadBarrels.isEmpty()) {
				for (Barrel badBarrel : initBadBarrels) {
					if (badBarrel.getBody().regenerateBounds()) {
						Barrel.barrels.add(badBarrel);
					}
					// In case Barrel Block locations were missing and could not be recreated: do not add the barrel
				}

			}
			if (!initWakeups.isEmpty()) {
				Wakeup.wakeups.addAll(initWakeups);
			}
		});
	}

	public static boolean acquireDataLoadMutex() {
		int wait = 0;
		// Increment the Data Mutex if it is not -1
		while (BData.dataMutex.updateAndGet(i -> i >= 0 ? i + 1 : i) <= 0) {
			wait++;
			if (!BConfig.loadDataAsync || wait > 60) {
				Brewery.getInstance().errorLog("Could not load World Data, Mutex: " + BData.dataMutex.get());
				return false;
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return false;
			}
		}
		return true;
	}

	public static void releaseDataLoadMutex() {
		dataMutex.decrementAndGet();
	}
}
