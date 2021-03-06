package commands.poll;

import core.SubCommand;
import core.SubCommandFormattable;
import dataStructures.KittyChannel;
import dataStructures.KittyGuild;
import dataStructures.KittyRating;
import dataStructures.KittyRole;
import dataStructures.KittyUser;
import dataStructures.UserInput;

public class SubCommandAddOption extends SubCommand
{

	public SubCommandAddOption(KittyRole roleLevel, KittyRating contentRating) {super(roleLevel, contentRating);}

	@Override
	public SubCommandFormattable OnRun(KittyGuild guild, KittyChannel channel, KittyUser user, UserInput input) 
	{
		return new SubCommandFormattable (guild.addChoiceToPoll(input.args));
	}
	
}