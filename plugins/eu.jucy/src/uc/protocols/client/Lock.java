package uc.protocols.client;

import java.io.IOException;

import logger.LoggerFactory;
import org.apache.log4j.Logger;


import uc.protocols.DCProtocol;

public class Lock extends AbstractNMDCClientProtocolCommand {

	
	private static final Logger logger = LoggerFactory.make();
	
	

	@Override
	public void handle(ClientProtocol client,String command) throws IOException {
		logger.debug(command + "  "+ client.isServer());
		
		int i;
		if (client.isServer() && (i = command.lastIndexOf("Ref=")) > 0 ) {
			String addy = command.substring(i+4).trim();
			client.setHubaddy(addy); //--> TODO verify user via hubaddress..i.e. have we choosen the correct one 
			logger.debug(addy);
		}
		
		Supports.sendSupports(client);
		Direction.sendDirectionString(client);

	
		client.sendUnmodifiedRaw(DCProtocol.generateKey(command,client.getCharset()));
		client.increaseLoginLevel();
		
		client.removeCommand(this);
		
	}
	


}
