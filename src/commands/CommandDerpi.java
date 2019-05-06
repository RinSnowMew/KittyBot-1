package commands;

import core.*;
import dataStructures.KittyChannel;
import dataStructures.KittyGuild;
import dataStructures.KittyRating;
import dataStructures.KittyRole;
import dataStructures.KittyUser;
import dataStructures.Response;
import dataStructures.UserInput;
import network.*;

public class CommandDerpi extends Command
{
	NetworkDerpi searcher = new NetworkDerpi(); 
	
	// Required constructor
	public CommandDerpi(KittyRole level, KittyRating rating) { super(level, rating); }
	
	@Override
	public String HelpText() { return "Will search derpibooru for the tags entered, if KittyBot's content filter allows it"; }
	
	@Override
	public void OnRun(KittyGuild guild, KittyChannel channel, KittyUser user, UserInput input, Response res)
	{
		if(guild.contentRating == KittyRating.Filtered)
		{
			res.Call(searcher.getDerpi(input.args + " safe").toString());
		}
		else
		{
			if(input.args == null || input.args.length() == 0)
				res.Call("Nothing to search for!");
			else
				res.Call(searcher.getDerpi(input.args).toString());
		}
	}
}