/*
 * This file is part of GriefPrevention, licensed under the MIT License (MIT).
 *
 * Copyright (c) Ryan Hamshire
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.ryanhamshire.GriefPrevention;

import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.service.user.UserStorageService;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

//manages data stored in the file system
public class FlatFileDataStore extends DataStore {

    private final static String claimDataFolderPath = dataLayerFolderPath + File.separator + "ClaimData";
    private final static String nextClaimIdFilePath = claimDataFolderPath + File.separator + "_nextClaimID";
    private final static String schemaVersionFilePath = dataLayerFolderPath + File.separator + "_schemaVersion";

    static boolean hasData() {
        File claimsDataFolder = new File(claimDataFolderPath);

        return claimsDataFolder.exists();
    }

    // initialization!
    FlatFileDataStore() throws Exception {
        this.initialize();
    }

    @Override
            void initialize() throws Exception {
        // ensure data folders exist
        boolean newDataStore = false;
        File playerDataFolder = new File(playerDataFolderPath);
        File claimDataFolder = new File(claimDataFolderPath);
        if (!playerDataFolder.exists() || !claimDataFolder.exists()) {
            newDataStore = true;
            playerDataFolder.mkdirs();
            claimDataFolder.mkdirs();
        }

        // if there's no data yet, then anything written will use the schema
        // implemented by this code
        if (newDataStore) {
            this.setSchemaVersion(DataStore.latestSchemaVersion);
        }

        // load group data into memory
        File[] files = playerDataFolder.listFiles();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.isFile())
                continue; // avoids folders

            // all group data files start with a dollar sign. ignoring the rest,
            // which are player data files.
            if (!file.getName().startsWith("$"))
                continue;

            String groupName = file.getName().substring(1);
            if (groupName == null || groupName.isEmpty())
                continue; // defensive coding, avoid unlikely cases

            BufferedReader inStream = null;
            try {
                inStream = new BufferedReader(new FileReader(file.getAbsolutePath()));
                String line = inStream.readLine();

                int groupBonusBlocks = Integer.parseInt(line);

                this.permissionToBonusBlocksMap.put(groupName, groupBonusBlocks);
            } catch (Exception e) {
                StringWriter errors = new StringWriter();
                e.printStackTrace(new PrintWriter(errors));
                GriefPrevention.AddLogEntry(errors.toString(), CustomLogEntryTypes.Exception);
            }

            try {
                if (inStream != null)
                    inStream.close();
            } catch (IOException exception) {
            }
        }

        // load next claim number from file
        File nextClaimIdFile = new File(nextClaimIdFilePath);
        if (nextClaimIdFile.exists()) {
            BufferedReader inStream = null;
            try {
                inStream = new BufferedReader(new FileReader(nextClaimIdFile.getAbsolutePath()));

                // read the id
                String line = inStream.readLine();

                // try to parse into a long value
                this.nextClaimID = Long.parseLong(line);
            } catch (Exception e) {
            }

            try {
                if (inStream != null)
                    inStream.close();
            } catch (IOException exception) {
            }
        }

        // if converting up from schema version 0, rename player data files
        // using UUIDs instead of player names
        // get a list of all the files in the claims data folder
        if (this.getSchemaVersion() == 0) {
            files = playerDataFolder.listFiles();
            ArrayList<String> namesToConvert = new ArrayList<String>();
            for (File playerFile : files) {
                namesToConvert.add(playerFile.getName());
            }

            // rename files
            for (File playerFile : files) {
                String currentFilename = playerFile.getName();

                // try to convert player name to UUID
                Optional<User> player = null;
                try {
                    player = GriefPrevention.instance.game.getServiceManager().provide(UserStorageService.class).get().get(currentFilename);

                    // if successful, rename the file using the UUID
                    if (player.isPresent()) {
                        playerFile.renameTo(new File(playerDataFolder, player.get().toString()));
                    }
                } catch (Exception ex) {
                }
            }
        }

        // load claims data into memory
        // get a list of all the files in the claims data folder
        files = claimDataFolder.listFiles();

        if (this.getSchemaVersion() <= 1) {
            this.loadClaimData_Legacy(files);
        } else {
            this.loadClaimData(files);
        }

        super.initialize();
    }

    void loadClaimData_Legacy(File[] files) throws Exception {
        List<World> validWorlds = (List<World>) GriefPrevention.instance.game.getServer().getWorlds();

        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) // avoids folders
            {
                // skip any file starting with an underscore, to avoid special
                // files not representing land claims
                if (files[i].getName().startsWith("_"))
                    continue;

                // the filename is the claim ID. try to parse it
                long claimID;

                try {
                    claimID = Long.parseLong(files[i].getName());
                }

                // because some older versions used a different file name
                // pattern before claim IDs were introduced,
                // those files need to be "converted" by renaming them to a
                // unique ID
                catch (Exception e) {
                    claimID = this.nextClaimID;
                    this.incrementNextClaimID();
                    File newFile = new File(claimDataFolderPath + File.separator + String.valueOf(this.nextClaimID));
                    files[i].renameTo(newFile);
                    files[i] = newFile;
                }

                BufferedReader inStream = null;
                try {
                    Claim topLevelClaim = null;

                    inStream = new BufferedReader(new FileReader(files[i].getAbsolutePath()));
                    String line = inStream.readLine();

                    while (line != null) {
                        // skip any SUB:### lines from previous versions
                        if (line.toLowerCase().startsWith("sub:")) {
                            line = inStream.readLine();
                        }

                        // skip any UUID lines from previous versions
                        Matcher match = uuidpattern.matcher(line.trim());
                        if (match.find()) {
                            line = inStream.readLine();
                        }

                        // first line is lesser boundary corner location
                        Location<World> lesserBoundaryCorner = this.locationFromString(line, validWorlds);

                        // second line is greater boundary corner location
                        line = inStream.readLine();
                        Location<World> greaterBoundaryCorner = this.locationFromString(line, validWorlds);

                        // third line is owner name
                        line = inStream.readLine();
                        String ownerName = line;
                        Optional<User> owner = null;
                        if (ownerName.isEmpty() || ownerName.startsWith("--")) {
                            owner = Optional.empty(); // administrative land claim or
                                            // subdivision
                        } else if (this.getSchemaVersion() == 0) {
                            owner = GriefPrevention.instance.game.getServiceManager().provide(UserStorageService.class).get().get(ownerName);
                            if (!owner.isPresent()) {
                                GriefPrevention.AddLogEntry("Couldn't resolve this name to a UUID: " + ownerName + ".");
                                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
                            }
                        }

                        // fourth line is list of builders
                        line = inStream.readLine();
                        List<String> builderNames = Arrays.asList(line.split(";"));
                        builderNames = this.convertNameListToUUIDList(builderNames);

                        // fifth line is list of players who can access
                        // containers
                        line = inStream.readLine();
                        List<String> containerNames = Arrays.asList(line.split(";"));
                        containerNames = this.convertNameListToUUIDList(containerNames);

                        // sixth line is list of players who can use buttons and
                        // switches
                        line = inStream.readLine();
                        List<String> accessorNames = Arrays.asList(line.split(";"));
                        accessorNames = this.convertNameListToUUIDList(accessorNames);

                        // seventh line is list of players who can grant
                        // permissions
                        line = inStream.readLine();
                        if (line == null)
                            line = "";
                        List<String> managerNames = Arrays.asList(line.split(";"));
                        managerNames = this.convertNameListToUUIDList(managerNames);

                        // skip any remaining extra lines, until the "==="
                        // string, indicating the end of this claim or
                        // subdivision
                        line = inStream.readLine();
                        while (line != null && !line.contains("==="))
                            line = inStream.readLine();

                        // build a claim instance from those data
                        // if this is the first claim loaded from this file,
                        // it's the top level claim
                        if (topLevelClaim == null) {
                            // instantiate
                            topLevelClaim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, owner.get().getUniqueId(), builderNames, containerNames,
                                    accessorNames, managerNames, claimID);

                            topLevelClaim.modifiedDate = new Date(files[i].lastModified());
                            this.addClaim(topLevelClaim, false);
                        }

                        // otherwise there's already a top level claim, so this
                        // must be a subdivision of that top level claim
                        else {
                            Claim subdivision = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, null, builderNames, containerNames,
                                    accessorNames, managerNames, null);

                            subdivision.modifiedDate = new Date(files[i].lastModified());
                            subdivision.parent = topLevelClaim;
                            topLevelClaim.children.add(subdivision);
                            subdivision.inDataStore = true;
                        }

                        // move up to the first line in the next subdivision
                        line = inStream.readLine();
                    }

                    inStream.close();
                }

                // if there's any problem with the file's content, log an error
                // message and skip it
                catch (Exception e) {
                    if (e.getMessage().contains("World not found")) {
                        inStream.close();
                        files[i].delete();
                    } else {
                        StringWriter errors = new StringWriter();
                        e.printStackTrace(new PrintWriter(errors));
                        GriefPrevention.AddLogEntry(files[i].getName() + " " + errors.toString(), CustomLogEntryTypes.Exception);
                    }
                }

                try {
                    if (inStream != null)
                        inStream.close();
                } catch (IOException exception) {
                }
            }
        }
    }

    void loadClaimData(File[] files) throws Exception {
        ConcurrentHashMap<Claim, Long> orphans = new ConcurrentHashMap<Claim, Long>();
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) // avoids folders
            {
                // skip any file starting with an underscore, to avoid special
                // files not representing land claims
                if (files[i].getName().startsWith("_"))
                    continue;

                // delete any which don't end in .hocon
                if (!files[i].getName().endsWith(".hocon")) {
                    files[i].delete();
                    continue;
                }

                // the filename is the claim ID. try to parse it
                long claimID;

                try {
                    claimID = Long.parseLong(files[i].getName().split("\\.")[0]);
                }

                // because some older versions used a different file name
                // pattern before claim IDs were introduced,
                // those files need to be "converted" by renaming them to a
                // unique ID
                catch (Exception e) {
                    claimID = this.nextClaimID;
                    this.incrementNextClaimID();
                    File newFile = new File(claimDataFolderPath + File.separator + String.valueOf(this.nextClaimID) + ".hocon");
                    files[i].renameTo(newFile);
                    files[i] = newFile;
                }

                try {
                    ArrayList<Long> out_parentID = new ArrayList<Long>(); // hacky
                                                                          // output
                                                                          // parameter
                    Claim claim = this.loadClaim(files[i], out_parentID, claimID);
                    if (out_parentID.size() == 0 || out_parentID.get(0) == -1) {
                        this.addClaim(claim, false);
                    } else {
                        orphans.put(claim, out_parentID.get(0));
                    }
                }

                // if there's any problem with the file's content, log an error
                // message and skip it
                catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("World not found")) {
                        files[i].delete();
                    } else {
                        StringWriter errors = new StringWriter();
                        e.printStackTrace(new PrintWriter(errors));
                        GriefPrevention.AddLogEntry(files[i].getName() + " " + errors.toString(), CustomLogEntryTypes.Exception);
                    }
                }
            }
        }

        // link children to parents
        for (Claim child : orphans.keySet()) {
            Claim parent = this.getClaim(orphans.get(child));
            if (parent != null) {
                child.parent = parent;
                this.addClaim(child, false);
            }
        }
    }

    Claim loadClaim(File file, ArrayList<Long> out_parentID, long claimID) throws IOException, Exception {
        return this.loadClaim(file, out_parentID, file.lastModified(), claimID, (List<World>) GriefPrevention.instance.game.getServer().getWorlds());
    }


    Claim loadClaim(File claimFile, ArrayList<Long> out_parentID, long lastModifiedDate, long claimID, List<World> validWorlds)
            throws Exception {
        Claim claim;

        HoconConfigurationLoader configurationLoader = HoconConfigurationLoader.builder().setFile(claimFile).build();
        CommentedConfigurationNode mainNode = configurationLoader.load();

        // boundaries
        Location<World> lesserBoundaryCorner = this.locationFromString(mainNode.getNode("Lesser Boundary Corner").getString(), validWorlds);
        Location<World> greaterBoundaryCorner = this.locationFromString(mainNode.getNode("Greater Boundary Corner").getString(), validWorlds);

        // owner
        String ownerIdentifier = mainNode.getNode("Owner").getString();
        UUID ownerID = null;
        if (!ownerIdentifier.isEmpty()) {
            try {
                ownerID = UUID.fromString(ownerIdentifier);
            } catch (Exception ex) {
                GriefPrevention.AddLogEntry("Error - this is not a valid UUID: " + ownerIdentifier + ".");
                GriefPrevention.AddLogEntry("  Converted land claim to administrative @ " + lesserBoundaryCorner.toString());
            }
        }

        List<String> builders = mainNode.getNode("Builders").getList(new TypeToken<String>() {
        });
        List<String> containers = mainNode.getNode("Containers").getList(new TypeToken<String>() {
        });
        List<String> accessors = mainNode.getNode("Accessors").getList(new TypeToken<String>() {
        });
        List<String> managers = mainNode.getNode("Managers").getList(new TypeToken<String>() {
        });
        out_parentID.add(mainNode.getNode("Parent Claim ID").getLong(-1L));

        // instantiate
        claim = new Claim(lesserBoundaryCorner, greaterBoundaryCorner, ownerID, builders, containers, accessors, managers, claimID);
        claim.modifiedDate = new Date(lastModifiedDate);
        claim.id = claimID;

        return claim;
    }

    CommentedConfigurationNode getHoconForClaim(Claim claim, CommentedConfigurationNode mainNode) {

        // boundaries
        mainNode.getNode("Lesser Boundary Corner").setValue(this.locationToString(claim.lesserBoundaryCorner));
        mainNode.getNode("Greater Boundary Corner").setValue(this.locationToString(claim.greaterBoundaryCorner));

        // owner
        String ownerID = "";
        if (claim.ownerID != null)
            ownerID = claim.ownerID.toString();
        mainNode.getNode("Owner").setValue(ownerID);

        ArrayList<String> builders = new ArrayList<String>();
        ArrayList<String> containers = new ArrayList<String>();
        ArrayList<String> accessors = new ArrayList<String>();
        ArrayList<String> managers = new ArrayList<String>();
        claim.getPermissions(builders, containers, accessors, managers);

        mainNode.getNode("Builders").setValue(builders);
        mainNode.getNode("Containers").setValue(containers);
        mainNode.getNode("Accessors").setValue(accessors);
        mainNode.getNode("Managers").setValue(managers);

        Long parentID = -1L;
        if (claim.parent != null) {
            parentID = claim.parent.id;
        }

        mainNode.getNode("Parent Claim ID").setValue(parentID);

        return mainNode;
    }

    @Override
    synchronized void writeClaimToStorage(Claim claim) {
        String claimID = String.valueOf(claim.id);

        try {
            // open the claim's file
            File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".hocon");
            HoconConfigurationLoader configurationLoader = HoconConfigurationLoader.builder().setFile(claimFile).build();
            CommentedConfigurationNode data = this.getHoconForClaim(claim, configurationLoader.load());
            configurationLoader.save(data);
        }

        // if any problem, log it
        catch (Exception e) {
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            GriefPrevention.AddLogEntry(claimID + " " + errors.toString(), CustomLogEntryTypes.Exception);
        }
    }

    // deletes a claim from the file system
    @Override
    synchronized void deleteClaimFromSecondaryStorage(Claim claim) {
        String claimID = String.valueOf(claim.id);

        // remove from disk
        File claimFile = new File(claimDataFolderPath + File.separator + claimID + ".hocon");
        if (claimFile.exists() && !claimFile.delete()) {
            GriefPrevention.AddLogEntry("Error: Unable to delete claim file \"" + claimFile.getAbsolutePath() + "\".");
        }
    }

    @Override
    synchronized PlayerData getPlayerDataFromStorage(UUID playerID) {
        File playerFile = new File(playerDataFolderPath + File.separator + playerID.toString());

        PlayerData playerData = new PlayerData();
        playerData.playerID = playerID;

        // if it exists as a file, read the file
        if (playerFile.exists()) {
            boolean needRetry = false;
            int retriesRemaining = 5;
            Exception latestException = null;
            do {
                try {
                    needRetry = false;

                    // read the file content and immediately close it
                    List<String> lines = Files.readLines(playerFile, Charset.forName("UTF-8"));
                    Iterator<String> iterator = lines.iterator();

                    // first line is last login timestamp
                    String lastLoginTimestampString = iterator.next();

                    // convert that to a date and store it
                    DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss");
                    try {
                        playerData.setLastLogin(dateFormat.parse(lastLoginTimestampString));
                    } catch (ParseException parseException) {
                        GriefPrevention.AddLogEntry("Unable to load last login for \"" + playerFile.getName() + "\".");
                        playerData.setLastLogin(null);
                    }

                    // second line is accrued claim blocks
                    String accruedBlocksString = iterator.next();

                    // convert that to a number and store it
                    playerData.setAccruedClaimBlocks(Integer.parseInt(accruedBlocksString));

                    // third line is any bonus claim blocks granted by
                    // administrators
                    String bonusBlocksString = iterator.next();

                    // convert that to a number and store it
                    playerData.setBonusClaimBlocks(Integer.parseInt(bonusBlocksString));

                    // fourth line is a double-semicolon-delimited list of
                    // claims, which is currently ignored
                    // String claimsString = inStream.readLine();
                    // iterator.next();
                }

                // if there's any problem with the file's content, retry up to 5
                // times with 5 milliseconds between
                catch (Exception e) {
                    latestException = e;
                    needRetry = true;
                    retriesRemaining--;
                }

                try {
                    if (needRetry)
                        Thread.sleep(5);
                } catch (InterruptedException exception) {
                }

            } while (needRetry && retriesRemaining >= 0);

            // if last attempt failed, log information about the problem
            if (needRetry) {
                StringWriter errors = new StringWriter();
                latestException.printStackTrace(new PrintWriter(errors));
                GriefPrevention.AddLogEntry(playerID + " " + errors.toString(), CustomLogEntryTypes.Exception);
            }
        }

        return playerData;
    }

    // saves changes to player data. MUST be called after you're done making
    // changes, otherwise a reload will lose them
    @Override
    public void overrideSavePlayerData(UUID playerID, PlayerData playerData) {
        // never save data for the "administrative" account. null for claim
        // owner ID indicates administrative account
        if (playerID == null)
            return;

        StringBuilder fileContent = new StringBuilder();
        try {
            // first line is last login timestamp
            if (playerData.getLastLogin() == null)
                playerData.setLastLogin(new Date());
            DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss");
            fileContent.append(dateFormat.format(playerData.getLastLogin()));
            fileContent.append("\n");

            // second line is accrued claim blocks
            fileContent.append(String.valueOf(playerData.getAccruedClaimBlocks()));
            fileContent.append("\n");

            // third line is bonus claim blocks
            fileContent.append(String.valueOf(playerData.getBonusClaimBlocks()));
            fileContent.append("\n");

            // fourth line is blank
            fileContent.append("\n");

            // write data to file
            File playerDataFile = new File(playerDataFolderPath + File.separator + playerID.toString());
            Files.write(fileContent.toString().getBytes("UTF-8"), playerDataFile);
        }

        // if any problem, log it
        catch (Exception e) {
            GriefPrevention
                    .AddLogEntry("GriefPrevention: Unexpected exception saving data for player \"" + playerID.toString() + "\": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    synchronized void incrementNextClaimID() {
        // increment in memory
        this.nextClaimID++;

        BufferedWriter outStream = null;

        try {
            // open the file and write the new value
            File nextClaimIdFile = new File(nextClaimIdFilePath);
            nextClaimIdFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(nextClaimIdFile));

            outStream.write(String.valueOf(this.nextClaimID));
        }

        // if any problem, log it
        catch (Exception e) {
            GriefPrevention.AddLogEntry("Unexpected exception saving next claim ID: " + e.getMessage());
            e.printStackTrace();
        }

        // close the file
        try {
            if (outStream != null)
                outStream.close();
        } catch (IOException exception) {
        }
    }

    // grants a group (players with a specific permission) bonus claim blocks as
    // long as they're still members of the group
    @Override
    synchronized void saveGroupBonusBlocks(String groupName, int currentValue) {
        // write changes to file to ensure they don't get lost
        BufferedWriter outStream = null;
        try {
            // open the group's file
            File groupDataFile = new File(playerDataFolderPath + File.separator + "$" + groupName);
            groupDataFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(groupDataFile));

            // first line is number of bonus blocks
            outStream.write(String.valueOf(currentValue));
            outStream.newLine();
        }

        // if any problem, log it
        catch (Exception e) {
            GriefPrevention.AddLogEntry("Unexpected exception saving data for group \"" + groupName + "\": " + e.getMessage());
        }

        try {
            // close the file
            if (outStream != null) {
                outStream.close();
            }
        } catch (IOException exception) {
        }
    }

    synchronized void migrateData(DatabaseDataStore databaseStore) {
        // migrate claims
        for (int i = 0; i < this.claims.size(); i++) {
            Claim claim = this.claims.get(i);
            databaseStore.addClaim(claim, true);
            for (Claim child : claim.children) {
                databaseStore.addClaim(child, true);
            }
        }

        // migrate groups
        Iterator<String> groupNamesEnumerator = this.permissionToBonusBlocksMap.keySet().iterator();
        while (groupNamesEnumerator.hasNext()) {
            String groupName = groupNamesEnumerator.next();
            databaseStore.saveGroupBonusBlocks(groupName, this.permissionToBonusBlocksMap.get(groupName));
        }

        // migrate players
        File playerDataFolder = new File(playerDataFolderPath);
        File[] files = playerDataFolder.listFiles();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.isFile())
                continue; // avoids folders

            // all group data files start with a dollar sign. ignoring those,
            // already handled above
            if (file.getName().startsWith("$"))
                continue;

            // ignore special files
            if (file.getName().startsWith("_"))
                continue;
            if (file.getName().endsWith(".ignore"))
                continue;

            UUID playerID = UUID.fromString(file.getName());
            databaseStore.savePlayerData(playerID, this.getPlayerData(playerID));
            this.clearCachedPlayerData(playerID);
        }

        // migrate next claim ID
        if (this.nextClaimID > databaseStore.nextClaimID) {
            databaseStore.setNextClaimID(this.nextClaimID);
        }

        // rename player and claim data folders so the migration won't run again
        int i = 0;
        File claimsBackupFolder;
        File playersBackupFolder;
        do {
            String claimsFolderBackupPath = claimDataFolderPath;
            if (i > 0)
                claimsFolderBackupPath += String.valueOf(i);
            claimsBackupFolder = new File(claimsFolderBackupPath);

            String playersFolderBackupPath = playerDataFolderPath;
            if (i > 0)
                playersFolderBackupPath += String.valueOf(i);
            playersBackupFolder = new File(playersFolderBackupPath);
            i++;
        } while (claimsBackupFolder.exists() || playersBackupFolder.exists());

        File claimsFolder = new File(claimDataFolderPath);
        File playersFolder = new File(playerDataFolderPath);

        claimsFolder.renameTo(claimsBackupFolder);
        playersFolder.renameTo(playersBackupFolder);

        GriefPrevention
                .AddLogEntry("Backed your file system data up to " + claimsBackupFolder.getName() + " and " + playersBackupFolder.getName() + ".");
        GriefPrevention.AddLogEntry("If your migration encountered any problems, you can restore those data with a quick copy/paste.");
        GriefPrevention.AddLogEntry("When you're satisfied that all your data have been safely migrated, consider deleting those folders.");
    }

    @Override
    synchronized void close() {
    }

    @Override
            int getSchemaVersionFromStorage() {
        File schemaVersionFile = new File(schemaVersionFilePath);
        if (schemaVersionFile.exists()) {
            BufferedReader inStream = null;
            int schemaVersion = 0;
            try {
                inStream = new BufferedReader(new FileReader(schemaVersionFile.getAbsolutePath()));

                // read the version number
                String line = inStream.readLine();

                // try to parse into an int value
                schemaVersion = Integer.parseInt(line);
            } catch (Exception e) {
            }

            try {
                if (inStream != null)
                    inStream.close();
            } catch (IOException exception) {
            }

            return schemaVersion;
        } else {
            this.updateSchemaVersionInStorage(0);
            return 0;
        }
    }

    @Override
            void updateSchemaVersionInStorage(int versionToSet) {
        BufferedWriter outStream = null;

        try {
            // open the file and write the new value
            File schemaVersionFile = new File(schemaVersionFilePath);
            schemaVersionFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(schemaVersionFile));

            outStream.write(String.valueOf(versionToSet));
        }

        // if any problem, log it
        catch (Exception e) {
            GriefPrevention.AddLogEntry("Unexpected exception saving schema version: " + e.getMessage());
        }

        // close the file
        try {
            if (outStream != null)
                outStream.close();
        } catch (IOException exception) {
        }

    }
}
