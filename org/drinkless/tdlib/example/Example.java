//
// Copyright Aliaksei Levin (levlam@telegram.org), Arseny Smirnov (arseny30@gmail.com) 2014-2018
//
// Distributed under the Boost Software License, Version 1.0. (See accompanying
// file LICENSE_1_0.txt or copy at http://www.boost.org/LICENSE_1_0.txt)
//
package org.drinkless.tdlib.example;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.Log;
import org.drinkless.tdlib.TdApi;
import org.drinkless.tdlib.TdApi.Message;
import org.drinkless.tdlib.TdApi.MessageText;
import org.drinkless.tdlib.TdApi.Messages;

import java.io.IOError;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;

/**
 * Example class for TDLib usage from Java.
 */
public final class Example {
    private static Client client = null;

    private static TdApi.AuthorizationState authorizationState = null;
    private static volatile boolean haveAuthorization = false;
    private static volatile boolean quiting = false;

    private static final Client.ResultHandler defaultHandler = new DefaultHandler();
    private static final Lock authorizationLock = new ReentrantLock();
    private static final Condition gotAuthorization = authorizationLock.newCondition();

    private static final ConcurrentMap<Integer, TdApi.User> users = new ConcurrentHashMap<Integer, TdApi.User>();
    private static final ConcurrentMap<Integer, TdApi.BasicGroup> basicGroups = new ConcurrentHashMap<Integer, TdApi.BasicGroup>();
    private static final ConcurrentMap<Integer, TdApi.Supergroup> supergroups = new ConcurrentHashMap<Integer, TdApi.Supergroup>();
    private static final ConcurrentMap<Integer, TdApi.SecretChat> secretChats = new ConcurrentHashMap<Integer, TdApi.SecretChat>();

    private static final ConcurrentMap<Long, TdApi.Chat> chats = new ConcurrentHashMap<Long, TdApi.Chat>();
    private static final NavigableSet<OrderedChat> chatList = new TreeSet<OrderedChat>();
    private static boolean haveFullChatList = false;

    private static final ConcurrentMap<Integer, TdApi.UserFullInfo> usersFullInfo = new ConcurrentHashMap<Integer, TdApi.UserFullInfo>();
    private static final ConcurrentMap<Integer, TdApi.BasicGroupFullInfo> basicGroupsFullInfo = new ConcurrentHashMap<Integer, TdApi.BasicGroupFullInfo>();
    private static final ConcurrentMap<Integer, TdApi.SupergroupFullInfo> supergroupsFullInfo = new ConcurrentHashMap<Integer, TdApi.SupergroupFullInfo>();

    private static final String newLine = System.getProperty("line.separator");
    private static final String commandsLine = "How to use:" + newLine + "b <chatId> - Backup chat by chatId" + newLine + "gcs - Displays recent 20 chatIds. Use 'gcs 100' for more" + newLine +"lo - LogOut" + newLine + "q - Quit" + newLine;
    private static volatile String currentPrompt = null;

    static HashMap<Integer, String> chatIdToUsername = new HashMap<Integer, String>();
    static HashSet<Integer> userIds = new HashSet<Integer>();
    static ArrayList<String> chatLogMessagesList = new ArrayList<String>();
    static HashSet<Long> alreadyReadMessageIds = new HashSet<Long>();
	static volatile boolean requestNotDoneYet = true;
	static volatile boolean messagesLeft = true;
	private static  long newestMessageId;
	private static volatile long fromMessageId = 1;
	private static String chatName;
	private static String fileName;
	private static String chatLogStartDate;
	private static String chatLogEndDate;
	private static int messagesCount = 0;
    static {
        System.loadLibrary("tdjni");
    }

    private static void print(String str) {
        if (currentPrompt != null) {
            System.out.println("");
        }
        System.out.println(str);
        if (currentPrompt != null) {
            System.out.print(currentPrompt);
        }
    }

    private static void setChatOrder(TdApi.Chat chat, long order) {
        synchronized (chatList) {
            if (chat.order != 0) {
                boolean isRemoved = chatList.remove(new OrderedChat(chat.order, chat.id));
                assert isRemoved;
            }

            chat.order = order;

            if (chat.order != 0) {
                boolean isAdded = chatList.add(new OrderedChat(chat.order, chat.id));
                assert isAdded;
            }
        }
    }

    private static void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        if (authorizationState != null) {
            Example.authorizationState = authorizationState;
        }
        switch (Example.authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.TdlibParameters parameters = new TdApi.TdlibParameters();
                parameters.databaseDirectory = "tdlib";
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = true;
                parameters.apiId = 94575;
                parameters.apiHash = "a3406de8d171bb422bb6ddf3bbd800e2";
                parameters.systemLanguageCode = "en";
                parameters.deviceModel = "Desktop";
                parameters.systemVersion = "Unknown";
                parameters.applicationVersion = "1.0";
                parameters.enableStorageOptimizer = true;

                client.send(new TdApi.SetTdlibParameters(parameters), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR:
                client.send(new TdApi.CheckDatabaseEncryptionKey(), new AuthorizationRequestHandler());
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR: {
                String phoneNumber = promptString("Please enter phone number: ");
                client.send(new TdApi.SetAuthenticationPhoneNumber(phoneNumber, false, false), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR: {
                String code = promptString("Please enter authentication code: ");
                client.send(new TdApi.CheckAuthenticationCode(code, "", ""), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR: {
                String password = promptString("Please enter password: ");
                client.send(new TdApi.CheckAuthenticationPassword(password), new AuthorizationRequestHandler());
                break;
            }
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                haveAuthorization = true;
                authorizationLock.lock();
                try {
                    gotAuthorization.signal();
                } finally {
                    authorizationLock.unlock();
                }
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                haveAuthorization = false;
                print("Logging out");
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                haveAuthorization = false;
                print("Closing");
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                print("Closed");
                if (!quiting) {
                    client = Client.create(new UpdatesHandler(), null, null); // recreate client after previous has closed
                }
                break;
            default:
                System.err.println("Unsupported authorization state:" + newLine + Example.authorizationState);
        }
    }

    private static int toInt(String arg) {
        int result = 0;
        try {
            result = Integer.parseInt(arg);
        } catch (NumberFormatException ignored) {
        }
        return result;
    }

    private static String promptString(String prompt) {
        System.out.print(prompt);
        currentPrompt = prompt;
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String str = "";
        try {
            str = reader.readLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentPrompt = null;
        return str;
    }

    private static void getCommand() {
        String command = promptString(commandsLine);
        String[] commands = command.split(" ", 2);
        try {
            switch (commands[0]) {
            	case "b": {
            		Long chatId = Long.valueOf(commands[1]);
            		getChatNameById(chatId);
            		fileName = buildFileName();
            		long startTime = System.currentTimeMillis();
            		
            		retrieveStartAndEndDate(chatId);
            		writeToChatLogFile("Chat history \"" + chatName + "\"" + newLine + "Starting: " + chatLogStartDate + newLine + "Ending: "+chatLogEndDate + newLine + newLine);
            		
            		System.out.println("Saving chat history for \"" + chatName + "\"");
            		while(messagesLeft) {
            			retrieveMessages(chatId);
            		}
            		//Retrieve user names
            		for (Integer userId : userIds) {
            			retrieveUserName(userId);
            			replaceUserIdWithUserName(userId, chatIdToUsername.get(userId));
					}
            		long stopTime = System.currentTimeMillis();
            		long elapsedTime = stopTime - startTime;
            	    System.out.print(messagesCount +" messages saved. Runtime: " + elapsedTime/1000 + " seconds.				");
            		System.out.println("");
            		resetChatVariables();
            		break;
            	}
            	
                case "gcs": {
                    int limit = 20;
                    if (commands.length > 1) {
                        limit = toInt(commands[1]);
                    }
                    getChatList(limit);
                    break;
                }
                case "lo":
                    haveAuthorization = false;
                    client.send(new TdApi.LogOut(), defaultHandler);
                    break;
                case "q":
                    quiting = true;
                    haveAuthorization = false;
                    client.send(new TdApi.Close(), defaultHandler);
                    break;
                default:
                    System.err.println("Unsupported command: "
                    		+ "" + command);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            print("Not enough arguments");
        }
    }

	private static void retrieveUserName(Integer userId) {
		requestNotDoneYet = true;
		client.send(new TdApi.GetUser(userId), new Client.ResultHandler() {
			@Override
	        public void onResult(TdApi.Object object) {
	        	switch (object.getConstructor()) {
	        		case TdApi.User.CONSTRUCTOR: 
	            		TdApi.User user = (TdApi.User) object;
	            		String fullName =  "";
	            		if(user.firstName.length() > 0)
	            			fullName += user.firstName + " ";
	            		if(user.lastName.length() > 0)
	            			fullName += user.lastName + " ";
	            		if(user.username.length() > 0)
	            			fullName += "[@" + user.username + "]";
	            		chatIdToUsername.put(user.id, fullName.trim());
	            		break;
	        	}
	        	requestNotDoneYet = false;
	        }	
		});
		waitForResponse();
	}

	private static void retrieveStartAndEndDate(Long chatId) {
		requestNotDoneYet = true;
		client.send(new TdApi.GetChatHistory(chatId, 0, 0, 100, false), new Client.ResultHandler() { // Retrieve newest
																										// messageID
			@Override
			public void onResult(TdApi.Object object) {
				switch (object.getConstructor()) {
				case TdApi.Messages.CONSTRUCTOR:
					TdApi.Messages messages = (Messages) object;
					newestMessageId = messages.messages[0].id;
					chatLogEndDate = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
							.format(new java.util.Date((long) messages.messages[0].date * 1000));
					break;
				}
			}
		});

		client.send(new TdApi.GetChatHistory(chatId, 1, -1, 100, false), new Client.ResultHandler() { // Retrieve oldest
																										// messageID
			@Override
			public void onResult(TdApi.Object object) {
				switch (object.getConstructor()) {
				case TdApi.Messages.CONSTRUCTOR:
					TdApi.Messages messages = (Messages) object;
					chatLogStartDate = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
							.format(new java.util.Date((long) messages.messages[0].date * 1000));
					break;
				}
				requestNotDoneYet = false;
			}
		});
		waitForResponse();
	}

	private static void retrieveMessages(Long chatId) {
		requestNotDoneYet = true;
		chatLogMessagesList.clear();
		client.send(new TdApi.GetChatHistory(chatId, fromMessageId, -99, 100, false), new Client.ResultHandler() {
			@Override
			public void onResult(TdApi.Object object) {
				TdApi.Messages messages = (Messages) object;
				Message[] messagesArr = messages.messages;
				StringBuilder msgSb = new StringBuilder();
				for (Message msg : messagesArr) {
					if (alreadyReadMessageIds.contains(msg.id))
						continue;
					msgSb.setLength(0);
					msgSb.append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
							.format(new java.util.Date((long) msg.date * 1000)) + ", ");
					if (!userIds.contains(msg.senderUserId)) {
						userIds.add(msg.senderUserId);
					}
					msgSb.append(msg.senderUserId + ": ");
					if (msg.content.getConstructor() == TdApi.MessageText.CONSTRUCTOR) {
						TdApi.MessageText mt = (MessageText) msg.content;
						msgSb.append(mt.text.text);
					} else {
						msgSb.append(msg.content.getClass());
					}
					msgSb.append(newLine);
					alreadyReadMessageIds.add(msg.id);
					chatLogMessagesList.add(msgSb.toString());
					System.out.print(messagesCount + " messages saved. " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
							.format(new java.util.Date((long) msg.date * 1000)) + "\r");
					messagesCount++;
				}
				for (int i = chatLogMessagesList.size() - 1; i >= 0; i--) {
					writeToChatLogFile(chatLogMessagesList.get(i));
				}
				fromMessageId = messagesArr[0].id;
				requestNotDoneYet = false;
				if (chatLogMessagesList.isEmpty())
					messagesLeft = false;
			}
		});
		waitForResponse();
	}

	/*
     * Resets all variables needed for storing chat history
     */
    private static void resetChatVariables() {
    	fromMessageId = 1;
		messagesLeft = true; 
		alreadyReadMessageIds.clear();
		chatLogMessagesList.clear();
		chatName = "";
		fileName = "";
		chatLogStartDate = "";
		chatLogEndDate = "";
		messagesCount = 0;
		chatIdToUsername.clear();
		userIds.clear();
	}

	private static String buildFileName() {
    	File dir = new File("chatLogs/"+chatName);
    	dir.mkdirs();
		return "chatLogs/"+chatName+"/"+new SimpleDateFormat("yyyy_MM_dd_HHmmss").format(new Date()) +".txt";
	}

	private static void getChatNameById(Long chatId) {
    	requestNotDoneYet = true;
    	client.send(new TdApi.GetChat(chatId), new Client.ResultHandler() {
            @Override
            public void onResult(TdApi.Object object) {
            	switch (object.getConstructor()) {
                    case TdApi.Error.CONSTRUCTOR:
                        System.err.println("Receive an error for GetChats:" + newLine + object);
                        break;
                    case TdApi.Chat.CONSTRUCTOR:
                    	chatName = ((TdApi.Chat) object).title;
                    	requestNotDoneYet = false;
                        break;
                    default:
                        System.err.println("Receive wrong response from TDLib:" + newLine + object);
                }
            }
        });
    	waitForResponse();
	}

	private static void replaceUserIdWithUserName(Integer userId, String userName) {
    	if(userName.isEmpty())
    		userName = "Deleted Account";
		Path path = Paths.get(fileName);
    	Charset charset = StandardCharsets.UTF_8;

    	String content;
		try {
			content = new String(Files.readAllBytes(path), charset);
			content = content.replaceAll(String.valueOf(userId), Matcher.quoteReplacement(userName));
	    	Files.write(path, content.getBytes(charset));
		} catch (IOException e) {
			e.printStackTrace();
		}
    	
		
	}

	private static void waitForResponse() {
    	while(requestNotDoneYet)
			try {Thread.sleep(1);
			} catch (InterruptedException e) {e.printStackTrace();}
		return;
	}

	private static void writeToChatLogFile(String str) {

		try (BufferedWriter bw = new BufferedWriter(
				new OutputStreamWriter(new FileOutputStream(fileName, true), StandardCharsets.UTF_8))) {

			PrintWriter out = new PrintWriter(bw);
			out.print(str);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private static void getChatList(final int limit) {
        synchronized (chatList) {
            if (!haveFullChatList && limit > chatList.size()) {
                // have enough chats in the chat list or chat list is too small
                long offsetOrder = Long.MAX_VALUE;
                long offsetChatId = 0;
                if (!chatList.isEmpty()) {
                    OrderedChat last = chatList.last();
                    offsetOrder = last.order;
                    offsetChatId = last.chatId;
                }
                client.send(new TdApi.GetChats(offsetOrder, offsetChatId, limit - chatList.size()), new Client.ResultHandler() {
                    @Override
                    public void onResult(TdApi.Object object) {
                        switch (object.getConstructor()) {
                            case TdApi.Error.CONSTRUCTOR:
                                System.err.println("Receive an error for GetChats:" + newLine + object);
                                break;
                            case TdApi.Chats.CONSTRUCTOR:
                                long[] chatIds = ((TdApi.Chats) object).chatIds;
                                if (chatIds.length == 0) {
                                    synchronized (chatList) {
                                        haveFullChatList = true;
                                    }
                                }
                                // chats had already been received through updates, let's retry request
                                getChatList(limit);
                                break;
                            default:
                                System.err.println("Receive wrong response from TDLib:" + newLine + object);
                        }
                    }
                });
                return;
            }

            // have enough chats in the chat list to answer request
            java.util.Iterator<OrderedChat> iter = chatList.iterator();
            System.out.println();
            System.out.println("First " + limit + " chat(s) out of " + chatList.size() + " known chat(s):");
            for (int i = 0; i < limit; i++) {
                long chatId = iter.next().chatId;
                TdApi.Chat chat = chats.get(chatId);
                synchronized (chat) {
                    System.out.println(chatId + ": " + chat.title);
                }
            }
            print("");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // disable TDLib log
        Log.setVerbosityLevel(0);
        if (!Log.setFilePath("tdlib.log")) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
        // create client
        client = Client.create(new UpdatesHandler(), null, null);

        // main loop
        
        while (!quiting) {
            // await authorization
            authorizationLock.lock();
            try {
                while (!haveAuthorization) {
                    gotAuthorization.await();
                }
            } finally {
                authorizationLock.unlock();
            }

            while (haveAuthorization) {
                getCommand();
            }
        }
    }

    private static class OrderedChat implements Comparable<OrderedChat> {
        final long order;
        final long chatId;

        OrderedChat(long order, long chatId) {
            this.order = order;
            this.chatId = chatId;
        }

        @Override
        public int compareTo(OrderedChat o) {
            if (this.order != o.order) {
                return o.order < this.order ? -1 : 1;
            }
            if (this.chatId != o.chatId) {
                return o.chatId < this.chatId ? -1 : 1;
            }
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            OrderedChat o = (OrderedChat) obj;
            return this.order == o.order && this.chatId == o.chatId;
        }
    }

    private static class DefaultHandler implements Client.ResultHandler {
    	@Override
        public void onResult(TdApi.Object object) {
        	switch (object.getConstructor()) {
            	case TdApi.Messages.CONSTRUCTOR:
            		TdApi.Messages messages = (Messages) object;
                	Message[] messagesArr = messages.messages;
                	StringBuilder msgSb = new StringBuilder();
                	for (Message msg : messagesArr) {
                		if(alreadyReadMessageIds.contains(msg.id)) continue;
                		msgSb.setLength(0);
                		msgSb.append(new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new java.util.Date((long)msg.date*1000)) +", ");
                		if(!userIds.contains(msg.senderUserId)) {
                			userIds.add(msg.senderUserId);
                		}
                		msgSb.append(msg.senderUserId +": ");
                		if(msg.content.getConstructor() == TdApi.MessageText.CONSTRUCTOR) {
                			TdApi.MessageText mt = (MessageText) msg.content;
                			msgSb.append(mt.text.text);
                		}
                		else {
                			msgSb.append(msg.content.getClass());
                		}
                    	msgSb.append(newLine);
                    	alreadyReadMessageIds.add(msg.id);
                    	chatLogMessagesList.add(msgSb.toString());
                    	System.out.print(messagesCount +" messages saved. "+new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new java.util.Date((long)msg.date*1000))+"\r");
                    	messagesCount++;
    				}
                	for (int i = chatLogMessagesList.size()-1; i >= 0; i--) {
        				writeToChatLogFile(chatLogMessagesList.get(i));
					}
                	fromMessageId = messagesArr[0].id;
                	requestNotDoneYet = false;
                	if(chatLogMessagesList.isEmpty())
                		messagesLeft = false;
                	break;
            	}
        }

		
    }
    private static class UpdatesHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                    onAuthorizationStateUpdated(((TdApi.UpdateAuthorizationState) object).authorizationState);
                    break;

                case TdApi.UpdateUser.CONSTRUCTOR:
                    TdApi.UpdateUser updateUser = (TdApi.UpdateUser) object;
                    users.put(updateUser.user.id, updateUser.user);
                    break;
                case TdApi.UpdateUserStatus.CONSTRUCTOR:  {
                    TdApi.UpdateUserStatus updateUserStatus = (TdApi.UpdateUserStatus) object;
                    TdApi.User user = users.get(updateUserStatus.userId);
                    synchronized (user) {
                        user.status = updateUserStatus.status;
                    }
                    break;
                }
                case TdApi.UpdateBasicGroup.CONSTRUCTOR:
                    TdApi.UpdateBasicGroup updateBasicGroup = (TdApi.UpdateBasicGroup) object;
                    basicGroups.put(updateBasicGroup.basicGroup.id, updateBasicGroup.basicGroup);
                    break;
                case TdApi.UpdateSupergroup.CONSTRUCTOR:
                    TdApi.UpdateSupergroup updateSupergroup = (TdApi.UpdateSupergroup) object;
                    supergroups.put(updateSupergroup.supergroup.id, updateSupergroup.supergroup);
                    break;
                case TdApi.UpdateSecretChat.CONSTRUCTOR:
                    TdApi.UpdateSecretChat updateSecretChat = (TdApi.UpdateSecretChat) object;
                    secretChats.put(updateSecretChat.secretChat.id, updateSecretChat.secretChat);
                    break;

                case TdApi.UpdateNewChat.CONSTRUCTOR: {
                    TdApi.UpdateNewChat updateNewChat = (TdApi.UpdateNewChat) object;
                    TdApi.Chat chat = updateNewChat.chat;
                    synchronized (chat) {
                        chats.put(chat.id, chat);

                        long order = chat.order;
                        chat.order = 0;
                        setChatOrder(chat, order);
                    }
                    break;
                }
                case TdApi.UpdateChatTitle.CONSTRUCTOR: {
                    TdApi.UpdateChatTitle updateChat = (TdApi.UpdateChatTitle) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.title = updateChat.title;
                    }
                    break;
                }
                case TdApi.UpdateChatPhoto.CONSTRUCTOR: {
                    TdApi.UpdateChatPhoto updateChat = (TdApi.UpdateChatPhoto) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.photo = updateChat.photo;
                    }
                    break;
                }
                case TdApi.UpdateChatLastMessage.CONSTRUCTOR: {
                    TdApi.UpdateChatLastMessage updateChat = (TdApi.UpdateChatLastMessage) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastMessage = updateChat.lastMessage;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatOrder.CONSTRUCTOR: {
                    TdApi.UpdateChatOrder updateChat = (TdApi.UpdateChatOrder) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatIsPinned.CONSTRUCTOR: {
                    TdApi.UpdateChatIsPinned updateChat = (TdApi.UpdateChatIsPinned) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.isPinned = updateChat.isPinned;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateChatReadInbox.CONSTRUCTOR: {
                    TdApi.UpdateChatReadInbox updateChat = (TdApi.UpdateChatReadInbox) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadInboxMessageId = updateChat.lastReadInboxMessageId;
                        chat.unreadCount = updateChat.unreadCount;
                    }
                    break;
                }
                case TdApi.UpdateChatReadOutbox.CONSTRUCTOR: {
                    TdApi.UpdateChatReadOutbox updateChat = (TdApi.UpdateChatReadOutbox) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.lastReadOutboxMessageId = updateChat.lastReadOutboxMessageId;
                    }
                    break;
                }
                case TdApi.UpdateChatUnreadMentionCount.CONSTRUCTOR: {
                    TdApi.UpdateChatUnreadMentionCount updateChat = (TdApi.UpdateChatUnreadMentionCount) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdApi.UpdateMessageMentionRead.CONSTRUCTOR: {
                    TdApi.UpdateMessageMentionRead updateChat = (TdApi.UpdateMessageMentionRead) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.unreadMentionCount = updateChat.unreadMentionCount;
                    }
                    break;
                }
                case TdApi.UpdateChatReplyMarkup.CONSTRUCTOR: {
                    TdApi.UpdateChatReplyMarkup updateChat = (TdApi.UpdateChatReplyMarkup) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.replyMarkupMessageId = updateChat.replyMarkupMessageId;
                    }
                    break;
                }
                case TdApi.UpdateChatDraftMessage.CONSTRUCTOR: {
                    TdApi.UpdateChatDraftMessage updateChat = (TdApi.UpdateChatDraftMessage) object;
                    TdApi.Chat chat = chats.get(updateChat.chatId);
                    synchronized (chat) {
                        chat.draftMessage = updateChat.draftMessage;
                        setChatOrder(chat, updateChat.order);
                    }
                    break;
                }
                case TdApi.UpdateNotificationSettings.CONSTRUCTOR: {
                    TdApi.UpdateNotificationSettings update = (TdApi.UpdateNotificationSettings) object;
                    if (update.scope instanceof TdApi.NotificationSettingsScopeChat) {
                        TdApi.Chat chat = chats.get(((TdApi.NotificationSettingsScopeChat) update.scope).chatId);
                        synchronized (chat) {
                            chat.notificationSettings = update.notificationSettings;
                        }
                    }
                    break;
                }

                case TdApi.UpdateUserFullInfo.CONSTRUCTOR:
                    TdApi.UpdateUserFullInfo updateUserFullInfo = (TdApi.UpdateUserFullInfo) object;
                    usersFullInfo.put(updateUserFullInfo.userId, updateUserFullInfo.userFullInfo);
                    break;
                case TdApi.UpdateBasicGroupFullInfo.CONSTRUCTOR:
                    TdApi.UpdateBasicGroupFullInfo updateBasicGroupFullInfo = (TdApi.UpdateBasicGroupFullInfo) object;
                    basicGroupsFullInfo.put(updateBasicGroupFullInfo.basicGroupId, updateBasicGroupFullInfo.basicGroupFullInfo);
                    break;
                case TdApi.UpdateSupergroupFullInfo.CONSTRUCTOR:
                    TdApi.UpdateSupergroupFullInfo updateSupergroupFullInfo = (TdApi.UpdateSupergroupFullInfo) object;
                    supergroupsFullInfo.put(updateSupergroupFullInfo.supergroupId, updateSupergroupFullInfo.supergroupFullInfo);
                    break;
                default:
                    // print("Unsupported update:" + newLine + object);
            }
        }
    }

    private static class AuthorizationRequestHandler implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            switch (object.getConstructor()) {
                case TdApi.Error.CONSTRUCTOR:
                    System.err.println("Receive an error:" + newLine + object);
                    onAuthorizationStateUpdated(null); // repeat last action
                    break;
                case TdApi.Ok.CONSTRUCTOR:
                    // result is already received through UpdateAuthorizationState, nothing to do
                    break;
                default:
                    System.err.println("Receive wrong response from TDLib:" + newLine + object);
            }
        }
    }
}