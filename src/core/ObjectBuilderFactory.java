package core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Semaphore;

import javax.security.auth.login.LoginException;

import commands.*;
import core.lua.PluginManager;
import dataStructures.*;
import main.Main;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Emote;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import offline.Ref;
import utils.AdminControl;
import utils.GlobalLog;
import utils.LogFilter;

// NOTE(wisp): Isolated factory to assist with storage and caching if needed.
// This also minimizes the number of places JDA interacts with our codebase.
// As it stands, if the object name begins with Kitty, it's constructed here.
// TODO: Make all methods ID based instead of event based 
public class ObjectBuilderFactory 
{
	// Key: guild string id, Value: guild information
	private static HashMap<String, KittyGuild> guildCache;

	// Key: channel string id, Value: channel information
	private static HashMap<String, KittyChannel> channelCache;
	
	// Key: guild string id + user string id, Value: user information
	private static HashMap<String, KittyUser> userCache;
	
	// For tracking and managing object sync
	private static DatabaseManager database;
	
	// Stats tracking and whatnot... for setting stats too internally potentially
	private static Stats stats;
	
	// RPManger for tracking RP system
	private static RPManager rpManager; 
	
	// Plugin manager
	private static PluginManager pluginManager;

	// Localization classes - these are singletons, but should be initialized before almost all other 
	// things so their inclusion in the factory is to ensure they're started at the correct time.
	@SuppressWarnings("unused") private static LocStrings locStrings;
	@SuppressWarnings("unused") private static LocCommands locCommands;
	
	// Handles if we can or can't use specific commands, parsing a config file based on loc data to do so.
	private static CommandEnabler commandEnabler;
	
	// Lazy initialization multithreaded mutex stuff to prevent explosions.
	// TODO: Investigate using 'synchronized' instead potentially
	private static boolean hasInitialized;
	private static Semaphore initMutex = new Semaphore(1);
	private static JDA kitty;
	
	// This is it, this is how the lazy init starts!
	private static void LazyInit()
	{
		if(hasInitialized)
			return;
		
		try
		{
			initMutex.acquire();
			try
			{
				// structure initialization 
				// Construct necessary data structures.
				guildCache = new HashMap<String, KittyGuild>();
				userCache = new HashMap<String, KittyUser>();
				channelCache = new HashMap<String, KittyChannel>();
				database = null;
				stats = null;
				
				// Start by reading from things that are external. Because
				// we require these things to be resolved before the rest of the application,
				// we place them here.
				locStrings = new LocStrings();
				locCommands = new LocCommands();
			}
			finally
			{
				initMutex.release();
				hasInitialized = true;
			}
		}
		catch(InterruptedException ie)
		{
			GlobalLog.Error(LogFilter.Core, "Issue during object builder lazy initialization."
				+ " The factory was not initialized, and kitty will not be able to continue functionally.");
		}
	}
	
	  ////////////////////////
	 // Extraction Methods //
	////////////////////////
	
	// Explicitly locks: guildCache
	public static KittyGuild extractGuild(GuildMessageReceivedEvent event)
	{
		LazyInit();
		
		// Look up the guild. This process can only happen in a single-threaded way
		// because of the nature of the cache. We wait until the last second to 
		// look up the guild.
		String uid = event.getGuild().getId();
		
		List<Emote> emotes = event.getGuild().getEmotes();
		ArrayList<String> emotesString = new ArrayList<String>();
		String emote;
		String emoteFix; 
		for(int i = 0; i < emotes.size(); i++)
		{
			emote = emotes.get(i).toString();
			emoteFix = "<:" + emote.substring(2, emote.indexOf("("));
			emoteFix += ":" + emote.substring(emote.indexOf("(")+1, emote.length()-1) + ">";
			emotesString.add(emoteFix);
		}
		
		// once we're lazily initialized, we can synchronize w/ the 
		// guildCache object now instead of having to use a mutex.
		KittyGuild guild = null;
		synchronized (guildCache)
		{
			KittyGuild cachedGuild = guildCache.get(uid);
			if(cachedGuild != null)
			{
				guild = cachedGuild;
			}
			else
			{
				// Construct a new guild with defaults
				guild = new KittyGuild(uid, new AdminControl(event.getGuild()), emotesString);
				DatabaseManager.instance.globalRegister(guild);
				guildCache.put(uid, guild);
			}
		}

		return guild;
	}
	
	// Explicitly locks: guildCache
	public static KittyRole extractRole(GuildMessageReceivedEvent event)
	{
		LazyInit();

		// Looks up the user role. If none is found we check to see if they own
		// the guild if not, they're assumed to be
		// allowed to use the bot at a general level.
		KittyRole role = KittyRole.General;
		
		if(event.getAuthor().getId() == event.getGuild().getOwner().getUser().getId())
		{
			role = KittyRole.Admin;
		}
		
		String uid = event.getGuild().getId() + event.getAuthor().getId();
		synchronized(userCache)
		{
			KittyUser cachedUser = userCache.get(uid);
			if(cachedUser != null)
				role = cachedUser.GetRole();
		}
		
		return role;
	}
	
	// Explicitly locks: guildCache
	// Extracts the content rating information it can from the provided event.
	public static KittyRating extractContentRating(GuildMessageReceivedEvent event)
	{
		LazyInit();

		// Look up content rating of the guild, returns a safe content rating.
		KittyRating contentRating = KittyRating.Safe;
		String uid = event.getGuild().getId();
		synchronized(guildCache)
		{
			KittyGuild cachedGuild = guildCache.get(uid);
			if(cachedGuild != null)
				contentRating = cachedGuild.contentRating;
		}
		
		return contentRating;
	}
	
	// Implicitly locks guild cache by calling ExtractGuild
	public static KittyChannel extractChannel(GuildMessageReceivedEvent event)
	{
		LazyInit();
		
		String channelID = event.getChannel().getId();
		String guildID = event.getGuild().getId();
		KittyChannel channel = null;
		
		synchronized(channelCache)
		{
			KittyChannel cachedChannel = channelCache.get(channelID);
			
			if(cachedChannel != null)
			{
				channel = cachedChannel;
			}
			else
			{
				KittyGuild cachedGuild = guildCache.get(guildID);
				channel = new KittyChannel(channelID, cachedGuild);
				channelCache.put(channelID, channel);
			}
		}
		
		return channel;
	}
	
	// Implicitly locks guild cache by calling ExtractRole and ExtractGuild
	public static KittyUser extractUser(GuildMessageReceivedEvent event)
	{
		LazyInit();
		
		String uid = event.getGuild().getId() + event.getAuthor().getId();
		KittyUser user = null;
		synchronized(userCache)
		{			
			KittyUser cachedUser = userCache.get(uid);
			if(cachedUser != null)
			{
				updateUser(cachedUser, event.getMember());
				user = cachedUser;
			}
			else
			{
				KittyRole role = extractRole(event);
				KittyGuild guild = extractGuild(event);
				
				String name;
				if(event.getMember().getNickname() == null)
					name = event.getAuthor().getName();
				else
					name = event.getMember().getNickname();
				
				String discordID = event.getMember().getUser().getId(); 
				String avatarID = event.getAuthor().getAvatarUrl();
				user = new KittyUser(name, guild, role, uid, avatarID, discordID);
				DatabaseManager.instance.globalRegister(user);
				userCache.put(uid, user);
			}
		}
		
		if(event.getMessage().getMentionedMembers().isEmpty())
			return user; 
		
		Member mentioned;
		for(int i = 0; i < event.getMessage().getMentionedMembers().size(); i++)
		{
			mentioned = event.getMessage().getMentionedMembers().get(i);
			if(mentioned.getNickname() != null)
				extractUserByJDAUser(event.getGuild().getId(), mentioned.getNickname(), 
					mentioned.getUser().getId(), mentioned.getUser().getAvatarUrl(), mentioned.getUser().getId());
			else
				extractUserByJDAUser(event.getGuild().getId(), mentioned.getUser().getName(), 
						mentioned.getUser().getId(), mentioned.getUser().getAvatarUrl(), mentioned.getUser().getId());
		}
		
		return user;
	}
	
	// TODO: Clean up
	public static KittyUser extractUserByJDAUser(String guildID, String name, String userID, String avatarID, String discordID)
	{
		LazyInit();
		
		String uid = guildID + userID;
		KittyUser user = null;
		synchronized(userCache)
		{
			KittyUser cachedUser = userCache.get(uid);
			if(cachedUser != null)
			{
				if(name != null)
					cachedUser.name = name;
				
				cachedUser.avatarID = avatarID;
				user = cachedUser;
			}
			else
			{
				KittyRole role = KittyRole.General;
				KittyGuild guild = guildCache.get(guildID);
				user = new KittyUser(name, guild, role, uid, avatarID, discordID);
				DatabaseManager.instance.globalRegister(user);
				userCache.put(uid, user);
			}
		}
		
		return user; 
	}
	
	// There is some redundant lookup occurring. If the user isn't cached yet, then we construct them and cache them.
	// The assumption is made that the user does, in fact, exist.
	public static KittyUser getKittyUser(String guildID, String userID)
	{
		String uid = guildID + userID;
		KittyUser user = null;
		synchronized(userCache)
		{
			user = userCache.get(uid);
		}
		
		if(user == null)
		{
			Guild jdaGuild = kitty.getGuildById(guildID);
			Member jdaMember = jdaGuild.getMemberById(userID);
			User jdaUser = jdaMember.getUser();
			
			user = extractUserByJDAUser(guildID, jdaMember.getNickname(), jdaUser.getId(), jdaUser.getAvatarUrl(), jdaUser.getId());
			updateUser(user, jdaMember);
		}
		
		return user; 
	}
	
	public static void updateUser(KittyUser user,  Member member)
	{
		if(member.getNickname() == null)
		{
			user.name = member.getUser().getName();
		}
		else
		{
			user.name = member.getNickname();
		}
		
		user.avatarID = member.getUser().getAvatarUrl();
	}
	
	
	  //////////////////////////
	 // Construction Methods //
	//////////////////////////
	
	public static KittyCore ConstructKittyCore() throws LoginException, InterruptedException
	{
		LazyInit();
		
		kitty = new JDABuilder(AccountType.BOT).setToken(Ref.TestToken).buildBlocking();
		kitty.getPresence().setGame(Game.playing("with digital yarn"));
		kitty.addEventListener(new Main());
		
		return new KittyCore(kitty);
	}
	
	// Default construction of the command manager. In order to remotely resolve command enabling
	// and disabling, what we do is construct the commands with a localized pair that is checked against
	// the CommandEnabler object passed in. In theory, we could have multiple CommandManagers, tho we can
	// only have one CommandEnabler.
	public static CommandManager constructCommandManager(CommandEnabler commandEnabler)
	{
		LazyInit();
		
		CommandManager manager = new CommandManager(commandEnabler);
		
		// Dev
		manager.Register(LocCommands.stub("work"), new CommandDoWork(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("shutdown"), new CommandShutdown(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("stats"), new CommandStats(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("invite"), new CommandInvite(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("buildHelp"), new CommandHelpBuilder(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("tweet"), new CommandTweet(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("dbflush"), new CommandDBFlush(KittyRole.Dev, KittyRating.Safe));
		manager.Register(LocCommands.stub("dbstats"), new CommandDBStats(KittyRole.Dev, KittyRating.Safe));
		
		// Admin
		manager.Register(LocCommands.stub("rating"), new CommandRating(KittyRole.Admin, KittyRating.Safe));
		manager.Register(LocCommands.stub("indicator"), new CommandChangeIndicator(KittyRole.Admin, KittyRating.Safe));
		manager.Register(LocCommands.stub("guildroleallowed"), new CommandGuildRoleAllowed(KittyRole.Admin, KittyRating.Safe));
		manager.Register(LocCommands.stub("guildrolenotallowed"), new CommandGuildRoleNotAllowed(KittyRole.Admin, KittyRating.Safe));
		
		// Mod
		manager.Register(LocCommands.stub("poll"), new CommandPollManage(KittyRole.Mod, KittyRating.Safe));
		manager.Register(LocCommands.stub("givebeans"), new CommandGiveBeans(KittyRole.Mod, KittyRating.Safe));
		manager.Register(LocCommands.stub("rpg"), new CommandRPG(KittyRole.Mod, KittyRating.Safe));
		manager.Register(LocCommands.stub("rafflestart"), new CommandRaffleStart(KittyRole.Mod, KittyRating.Safe));
		manager.Register(LocCommands.stub("rafflespin"), new CommandRaffleSpin(KittyRole.Mod, KittyRating.Safe));
		manager.Register(LocCommands.stub("raffleend"), new CommandRaffleEnd(KittyRole.Mod, KittyRating.Safe));

		// General
		manager.Register(LocCommands.stub("fetch"), new CommandFetch(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("guildroleadd"), new CommandGuildRoleAdd(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("guildroleremove"), new CommandGuildRoleRemove(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("teey"), new CommandTeey(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("perish, thenperish"), new CommandPerish(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("yeet"), new CommandYeet(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("ping"), new CommandPing(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("boop"), new CommandBoop(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("roll"), new CommandRoll(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("choose"), new CommandChoose(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("help"), new CommandHelp(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("info, about"), new CommandInfo(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("vote"), new CommandPollVote(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("results"), new CommandPollResults(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("showpoll"), new CommandPollShow(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("wolfram"), new CommandWolfram(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("c++, g++, cplus, cpp"), new CommandColiru(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("java, jdoodle"), new CommandJDoodle(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("beans"), new CommandBeansShow(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("role"), new CommandRole(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("bet"), new CommandBetBeans(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("map"), new CommandMap(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("rpstart"), new CommandRPStart(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("rpend"), new CommandRPEnd(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("tony, stark, dontfeelgood, dontfeelsogood"), new CommandStark(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("blur"), new CommandBlurry(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("eightball, 8ball"), new CommandEightBall(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("catch"), new CommandCatch(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("guildrolelist"), new CommandGuildRoleList(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("bethistory"), new CommandBetHistory(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("crouton"), new CommandCrouton(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("benchmark, bench"), new CommandBenchmark(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("rafflejoin"), new CommandRaffleJoin(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("charactercreate"), new CommandCharacterCreate(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("searchcharacter"), new CommandCharacterSearch(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("charactereditbio"), new CommandCharacterEditBio(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("charactereditname"), new CommandCharacterEditName(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("charactereditURL"), new CommandCharacterEditURL(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("leaderboard"), new CommandLeaderboard(KittyRole.General, KittyRating.Safe));
		manager.Register(LocCommands.stub("color, colour"), new CommandColor(KittyRole.General, KittyRating.Safe));
		
		return manager;
	}
	
	// Constructs a CommandEnabler if it doesn't exist, and gets the existing one if it does. 
	public static CommandEnabler constructCommandEnabler()
	{
		LazyInit();

		if(commandEnabler == null)
			commandEnabler = new CommandEnabler();
		
		return commandEnabler;
	}
	
	// Default database manager construction. It can be constructed 
	// in different ways, and so we construct it outside of the constructor for 
	// the factory  since it doesn't have to be present / can be elsewhere. 
	// Effectively we cache the database here.
	public static DatabaseManager constructDatabaseManager()
	{
		LazyInit();
		
		if(database == null)
			database = new DatabaseManager();
		
		return database;
	}
	
	public static Stats constructStats(CommandManager manager)
	{
		LazyInit();
		
		if(stats == null)
			stats = new Stats(manager);
		
		return stats;
	}
	
	public static RPManager constructRPManager()
	{
		LazyInit();
		
		if(rpManager == null)
			rpManager = new RPManager();
		
		return rpManager;
	}
	
	public static PluginManager constructPluginManager()
	{
		LazyInit();
		
		if(pluginManager == null)
			pluginManager = new PluginManager("./plugins/");
		
		return pluginManager;
	}
	
	
	  /////////////////////
	 // Utility Methods //
	/////////////////////
	
	// Returns number of cached guilds (does not equal total users in the database, only what's in memory)
	public static Integer getGuildCount()
	{ 	synchronized(guildCache)
		{
			return guildCache.size();
		}
	}
	
	// Returns number of cached users (does not equal total users in the database, only what's in memory)
	public static Integer getUserCount()
	{ 	synchronized(userCache)
		{
			return userCache.size();
		}
	}
}