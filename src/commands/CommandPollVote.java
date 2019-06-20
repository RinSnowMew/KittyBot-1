package commands;

import core.*;
import dataStructures.*;

public class CommandPollVote extends Command
{
	public CommandPollVote(KittyRole level, KittyRating rating) { super(level, rating); }
	
	@Override
	public String getHelpText() { return LocStrings.stub("PollVoteInfo"); }
	
	@Override
	public void onRun(KittyGuild guild, KittyChannel channel, KittyUser user, UserInput input, Response res)
	{
		if(guild.polling)
		{
			if(guild.hasVoted.contains(user.uniqueID))
			{
				res.call(LocStrings.stub("PollVoteAlreadyVoted"));
				return;
			}
			try 
			{
				int voteNum = Integer.parseInt(input.args)-1;
				if(voteNum >= guild.choices.size() || voteNum < 0)
				{
					res.call(String.format(LocStrings.stub("PollVoteNotValidVote"), voteNum));
					return;
				}
				
				KittyPoll polled = guild.choices.get(voteNum);
				polled.votes++;
				guild.hasVoted.add(user.uniqueID);
				res.call(LocStrings.stub("PollVoteSuccess") + " `" + polled.choice + "`!");
				return;
			}
			catch (NumberFormatException e)
			{
				res.call(LocStrings.stub("PollVoteNotValidNumber"));
				return;
			}
		}
		
		res.call(LocStrings.stub("PollVoteNoPoll"));
	}
}
