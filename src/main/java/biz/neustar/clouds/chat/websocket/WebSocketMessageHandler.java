package biz.neustar.clouds.chat.websocket;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Iterator;

import javax.websocket.Session;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import biz.neustar.clouds.chat.InitFilter;
import biz.neustar.clouds.chat.exceptions.ConnectionNotFoundException;
import biz.neustar.clouds.chat.util.HexUtil;
import biz.neustar.clouds.chat.util.JsonUtil;
import biz.neustar.clouds.chat.util.XdiUtil;
import xdi2.client.constants.XDIClientConstants;
import xdi2.client.exceptions.Xdi2ClientException;
import xdi2.client.impl.websocket.XDIWebSocketClient;
import xdi2.core.bootstrap.XDIBootstrap;
import xdi2.core.syntax.CloudNumber;
import xdi2.core.syntax.XDIAddress;
import xdi2.core.syntax.XDIArc;
import xdi2.core.syntax.XDIStatement;
import xdi2.core.util.XDIAddressUtil;
import xdi2.discovery.XDIDiscoveryResult;
import xdi2.messaging.Message;
import xdi2.messaging.MessageEnvelope;
import xdi2.messaging.operations.Operation;
import xdi2.messaging.operations.SetOperation;
import xdi2.messaging.response.TransportMessagingResponse;

public class WebSocketMessageHandler implements javax.websocket.MessageHandler.Whole<String>, XDIWebSocketClient.Callback {

	private static final Logger log = LoggerFactory.getLogger(WebSocketMessageHandler.class);

	private static final Gson gson = new GsonBuilder()
			.setDateFormat(DateFormat.FULL, DateFormat.FULL)
			.disableHtmlEscaping()
			.serializeNulls()
			.create();

	private Session session;
	private XDIAddress child1;
	private XDIAddress child2;
	private CloudNumber ascn;
	private byte[] aspk;
	private XDIAddress aslc;
	private XDIWebSocketClient client;

	public WebSocketMessageHandler(Session session, XDIAddress child1, XDIAddress child2) {

		this.session = session;
		this.child1 = child1;
		this.child2 = child2;
	}

	@Override
	public void onMessage(String string) {

		// read line

		BufferedReader bufferedReader = new BufferedReader(new StringReader(string));
		String line;

		try {

			line = bufferedReader.readLine();
		} catch (IOException ex) {

			throw new RuntimeException(ex.getMessage(), ex);
		}

		log.info("Received line " + line + " from session " + this.session.getId());

		// ascn, aspk, aslc? connect!

		if (line.startsWith("{")) {

			try {

				this.connect(line);
			} catch (Exception ex) {

				throw new RuntimeException("Cannot connect: " + ex.getMessage(), ex);
			}
			return;
		} 

		// send message

		try {

			this.sendToXdi(line);
		} catch (Exception ex) {

			throw new RuntimeException("Cannot send to XDI: " + ex.getMessage(), ex);
		}
	}

	private void connect(String line) throws GeneralSecurityException, Xdi2ClientException {

		JsonObject json = gson.fromJson(line, JsonObject.class);

		this.ascn = CloudNumber.create(json.get("ascn").getAsString());
		log.info("Received ASCN: " + this.ascn);
		this.aspk = Base64.decodeBase64(json.get("aspk").getAsString().getBytes());
		log.info("Received ASPK: " + this.aspk + " (" + this.aspk.length + " bytes)");
		this.aslc = XDIAddress.create(json.get("aslc").getAsString());
		log.info("Received ASLC: " + this.aslc);

		// discovery

		XDIDiscoveryResult child1Discovery;

		try {

			child1Discovery = InitFilter.XDI_DISCOVERY_CLIENT.discover(child1, XDIClientConstants.WEBSOCKET_ENDPOINT_URI_TYPE);
			if (child1Discovery == null) throw new NullPointerException("Child 1 not found.");
		} catch (Exception ex) {

			throw new ConnectionNotFoundException("Cannot find discovery information for child1 " + child1 + ": " + ex.getMessage(), ex);
		}

		// open websocket connection

		this.client = new XDIWebSocketClient();
		this.client.setXdiWebSocketEndpointUri(child1Discovery.getXdiWebSocketEndpointUri());

		this.client.setCallback(this);

		// request $push

		CloudNumber child1CloudNumber = CloudNumber.fromXDIAddress(this.getChild1());
		XDIAddress channel = XDIAddress.create("" + child1CloudNumber + "#chat[$msg]");

		Message mAppToChild = XdiUtil.mAppToChild(child1CloudNumber, this.ascn, this.aslc);
		Operation connectOperation = mAppToChild.createConnectOperation(XDIBootstrap.PUSH_LINK_CONTRACT_TEMPLATE_ADDRESS);
		connectOperation.setVariableValue(XDIArc.create("{$push}"), channel);

		XdiUtil.mSign(mAppToChild, aspk);

		this.client.send(mAppToChild.getMessageEnvelope());
	}

	private void sendToXdi(String line) throws GeneralSecurityException, Xdi2ClientException {

		CloudNumber child1CloudNumber = CloudNumber.fromXDIAddress(this.getChild1());
		CloudNumber child2CloudNumber = CloudNumber.fromXDIAddress(this.getChild2());
		XDIAddress channel = XDIAddress.create("" + child2CloudNumber + "#chat$channel[$msg]");

		// create ME from child1 to child2

		Message mChild1ToChild2 = XdiUtil.mChildToChildChat(child1CloudNumber, child2CloudNumber);
		mChild1ToChild2.createSetOperation(XDIStatement.fromLiteralComponents(
				XDIAddressUtil.concatXDIAddresses(channel, XDIAddress.create("@~0<#text>")), 
				line));

		// create ME from app to child1

		Message mAppToChild1 = XdiUtil.mAppToChild(child1CloudNumber, this.ascn, this.aslc);
		mAppToChild1.createSendOperation(mChild1ToChild2.getMessageEnvelope().getGraph());

		XdiUtil.mSign(mAppToChild1, aspk);

		// send

		this.client.send(mAppToChild1.getMessageEnvelope());
	}

	private void sendToClient(WebSocketMessageHandler fromWebSocketMessageHandler, String line) {

		JsonObject jsonObject = new JsonObject();
		jsonObject.add("chatChild1", new JsonPrimitive(fromWebSocketMessageHandler.getChild1().toString()));
		jsonObject.add("chatChild2", new JsonPrimitive(fromWebSocketMessageHandler.getChild2().toString()));
		jsonObject.add("message", new JsonPrimitive(line));

		String string = JsonUtil.toString(jsonObject);

		this.session.getAsyncRemote().sendText(string);

		log.info("Sent JSON object " + string + " to session " + this.session.getId());
	}

	/*
	 * Getters and setters
	 */

	public Session getSession() {

		return this.session;
	}

	public XDIAddress getChild1() {

		return this.child1;
	}

	public XDIAddress getChild2() {

		return this.child2;
	}

	public CloudNumber getAscn() {

		return this.ascn;
	}

	public byte[] getAspk() {

		return this.aspk;
	}

	public XDIAddress getAslc() {

		return this.aslc;
	}

	@Override
	public void onMessageEnvelope(MessageEnvelope messageEnvelope) {

		log.debug("Received XDI message envelope: " + messageEnvelope);

		Message message = messageEnvelope.getMessages().next();
		Iterator<SetOperation> operations = message.getSetOperations();
		if (! operations.hasNext()) return;
		SetOperation operation = operations.next();
		Iterator<XDIStatement> statements = operation.getTargetXDIStatements();
		if (statements.hasNext()) return;
		XDIStatement statement = statements.next();

		this.sendToClient(this, statement.getLiteralData().toString());
	}

	@Override
	public void onMessagingResponse(TransportMessagingResponse messagingResponse) {

		log.debug("Received XDI messaging response: " + messagingResponse);

		this.sendToClient(this, messagingResponse.toString());
	}
}
