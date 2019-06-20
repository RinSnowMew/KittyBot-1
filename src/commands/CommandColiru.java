package commands;

import core.Command;
import core.LocStrings;
import dataStructures.*;
import network.NetworkColiru;

public class CommandColiru extends Command
{
	NetworkColiru compiler = new NetworkColiru();
	
	public CommandColiru(KittyRole level, KittyRating rating) { super(level, rating);}
	
	@Override
	public String getHelpText() { return LocStrings.stub("ColiruInfo"); }
	
	@Override
	public void onRun(KittyGuild guild, KittyChannel channel, KittyUser user, UserInput input, Response res)
	{
		if(input.args.trim().length() < 1)
		{
			res.call(LocStrings.stub("ColiruError"));
			return;
		}
		
		res.call(compiler.compileCPlus(input.args));
	}

}
