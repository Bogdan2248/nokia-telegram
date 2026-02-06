import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import java.io.*;
import java.util.*;

public class TelegramClient extends MIDlet implements CommandListener, Runnable {
    private Display display;
    private Form authForm, messageForm;
    private TextField phoneField, codeField, serverField, replyField;
    private Command sendCodeCmd, loginCmd, refreshChatsCmd, sendMsgCmd, backCmd, reactCmd, exitCmd;
    private List chatList, reactionEmojiList;
    private long currentChatId;
    private long lastMsgId = 0;
    private boolean isAutoRefreshRunning = false;

    public TelegramClient() {
        display = Display.getDisplay(this);
        
        authForm = new Form("Telegram Auth");
        phoneField = new TextField("Phone (+7...)", "+", 20, TextField.PHONENUMBER);
        codeField = new TextField("Code", "", 10, TextField.NUMERIC);
        serverField = new TextField("Server URL", "http://10.20.184.199:5000", 200, TextField.URL);
        
        sendCodeCmd = new Command("Send Code", Command.OK, 1);
        loginCmd = new Command("Login", Command.OK, 2);
        refreshChatsCmd = new Command("Refresh", Command.SCREEN, 3);
        backCmd = new Command("Back", Command.BACK, 1);
        exitCmd = new Command("Exit", Command.EXIT, 4);
        Command settingsCmd = new Command("Settings", Command.SCREEN, 5);
        
        authForm.append(phoneField);
        authForm.append(codeField);
        authForm.append(serverField);
        authForm.addCommand(sendCodeCmd);
        authForm.addCommand(loginCmd);
        authForm.addCommand(refreshChatsCmd);
        authForm.addCommand(exitCmd);
        authForm.setCommandListener(this);

        chatList = new List("Chats", List.IMPLICIT);
        chatList.addCommand(refreshChatsCmd);
        chatList.addCommand(settingsCmd);
        chatList.addCommand(exitCmd);
        chatList.setCommandListener(this);

        reactionEmojiList = new List("React with...", List.IMPLICIT);
        reactionEmojiList.append("👍", null);
        reactionEmojiList.append("❤️", null);
        reactionEmojiList.append("🔥", null);
        reactionEmojiList.append("👏", null);
        reactionEmojiList.append("😢", null);
        reactionEmojiList.addCommand(backCmd);
        reactionEmojiList.setCommandListener(this);

        messageForm = new Form("Messages");
        replyField = new TextField("Reply", "", 100, TextField.ANY);
        sendMsgCmd = new Command("Send", Command.OK, 1);
        reactCmd = new Command("React", Command.SCREEN, 2);
        messageForm.addCommand(sendMsgCmd);
        messageForm.addCommand(reactCmd);
        messageForm.addCommand(backCmd);
        messageForm.addCommand(settingsCmd);
        messageForm.addCommand(exitCmd);
        messageForm.setCommandListener(this);
    }

    protected void startApp() {
        display.setCurrent(authForm);
        if (!isAutoRefreshRunning) {
            isAutoRefreshRunning = true;
            new Thread(this).start();
        }
    }

    public void run() {
        while (isAutoRefreshRunning) {
            try {
                Thread.sleep(5000); // Автообновление каждые 5 сек
                if (display.getCurrent() == messageForm) {
                    loadMessages(currentChatId);
                } else if (display.getCurrent() == chatList) {
                    loadChats();
                }
            } catch (Exception e) {}
        }
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == sendCodeCmd) {
            new Thread() { public void run() { sendCode(); } }.start();
        } else if (c == loginCmd) {
            new Thread() { public void run() { login(); } }.start();
        } else if (c == refreshChatsCmd || (c == List.SELECT_COMMAND && d == chatList)) {
            if (c == List.SELECT_COMMAND) {
                String selected = chatList.getString(chatList.getSelectedIndex());
                int start = selected.lastIndexOf('[') + 1;
                int end = selected.lastIndexOf(']');
                currentChatId = Long.parseLong(selected.substring(start, end));
                new Thread() { public void run() { loadMessages(currentChatId); } }.start();
            } else {
                new Thread() { public void run() { loadChats(); } }.start();
            }
        } else if (c.getLabel().equals("Settings")) {
            display.setCurrent(authForm);
        } else if (c == backCmd) {
            if (d == reactionEmojiList) {
                display.setCurrent(messageForm);
            } else if (d == messageForm) {
                display.setCurrent(chatList);
            } else {
                display.setCurrent(authForm);
            }
        } else if (c == reactCmd) {
            display.setCurrent(reactionEmojiList);
        } else if (d == reactionEmojiList && c == List.SELECT_COMMAND) {
            final String emoji = reactionEmojiList.getString(reactionEmojiList.getSelectedIndex());
            new Thread() { public void run() { sendReaction(emoji); } }.start();
        } else if (c == sendMsgCmd) {
            new Thread() { public void run() { sendMessage(); } }.start();
        } else if (c == exitCmd) {
            destroyApp(true);
            notifyDestroyed();
        }
    }

    private String getBaseUrl() {
        String url = serverField.getString().trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private void sendCode() {
        String res = httpGet(getBaseUrl() + "/auth/send_code?phone=" + urlEncode(phoneField.getString()));
        showAlert("Server", res);
    }

    private void login() {
        String res = httpGet(getBaseUrl() + "/auth/login?phone=" + urlEncode(phoneField.getString()) + "&code=" + codeField.getString());
        if (res.indexOf("Logged in") != -1) {
            loadChats();
        } else {
            showAlert("Login Error", res);
        }
    }

    private void loadChats() {
        String res = httpGet(getBaseUrl() + "/chats");
        if (res == null || res.length() == 0) {
            showAlert("Error", "Empty response from server");
            return;
        }
        if (res.startsWith("[")) {
            chatList.deleteAll();
            try {
                int idx = 0;
                while ((idx = res.indexOf("\"id\":", idx)) != -1) {
                    // Парсим ID (может заканчиваться запятой или скобкой)
                    int idStart = idx + 5;
                    int idEnd = res.indexOf(",", idStart);
                    if (idEnd == -1) idEnd = res.indexOf("}", idStart);
                    if (idEnd == -1) break;
                    
                    String id = res.substring(idStart, idEnd).trim();
                    
                    // Парсим Name
                    int nameLabel = res.indexOf("\"name\":\"", idx);
                    if (nameLabel == -1) { idx = idEnd; continue; }
                    int nameStart = nameLabel + 8;
                    int nameEnd = res.indexOf("\"", nameStart);
                    if (nameEnd == -1) { idx = idEnd; continue; }
                    
                    String name = res.substring(nameStart, nameEnd);
                    chatList.append(name + " [" + id + "]", null);
                    idx = nameEnd;
                }
                
                if (chatList.size() == 0) {
                    showAlert("Chats", "No chats found or parsing error. Raw: " + (res.length() > 50 ? res.substring(0, 50) : res));
                } else {
                    display.setCurrent(chatList);
                }
            } catch (Exception e) {
                showAlert("Parse Error", e.getMessage() + "\nRaw: " + (res.length() > 50 ? res.substring(0, 50) : res));
            }
        } else {
            showAlert("Error", "Auth required or server error. Response: " + (res.length() > 50 ? res.substring(0, 50) : res));
        }
    }

    private void loadMessages(long chatId) {
        String res = httpGet(getBaseUrl() + "/messages?id=" + chatId);
        if (res == null || res.length() == 0 || res.startsWith("Error")) {
            showAlert("Error", "Could not load messages: " + res);
            return;
        }
        
        messageForm.deleteAll();
        try {
            int idx = 0;
            while ((idx = res.indexOf("\"from\":\"", idx)) != -1) {
                int fromStart = idx + 8;
                int fromEnd = res.indexOf("\"", fromStart);
                if (fromEnd == -1) break;
                String from = res.substring(fromStart, fromEnd);
                
                int replyIdx = res.indexOf("\"reply_to\":\"", idx);
                if (replyIdx != -1 && replyIdx < res.indexOf("}", idx)) {
                    int rStart = replyIdx + 12;
                    int rEnd = res.indexOf("\"", rStart);
                    if (rEnd != -1) {
                        String rText = res.substring(rStart, rEnd);
                        messageForm.append(new StringItem(null, " > Re: " + rText + "\n"));
                    }
                }

                int textLabel = res.indexOf("\"text\":\"", fromEnd);
                if (textLabel == -1) { idx = res.indexOf("}", idx); continue; }
                int textStart = textLabel + 8;
                int textEnd = res.indexOf("\"", textStart);
                if (textEnd == -1) { idx = res.indexOf("}", idx); continue; }
                String text = res.substring(textStart, textEnd);
                
                int photoIdx = res.indexOf("\"has_photo\":1", idx);
                String msgId = "0";
                int idIdx = res.indexOf("\"id\":", idx);
                if (idIdx != -1) {
                    int idEnd = res.indexOf(",", idIdx);
                    if (idEnd == -1) idEnd = res.indexOf("}", idIdx);
                    if (idEnd != -1) {
                        msgId = res.substring(idIdx + 5, idEnd).trim();
                        lastMsgId = Long.parseLong(msgId);
                    }
                }

                if (photoIdx != -1 && photoIdx < res.indexOf("}", idx)) {
                    messageForm.append(new StringItem(from + ": ", text + " [Photo]"));
                    try {
                        Image img = loadHttpImage(getBaseUrl() + "/photo?chat_id=" + chatId + "&msg_id=" + msgId);
                        if (img != null) messageForm.append(new ImageItem(null, img, ImageItem.LAYOUT_CENTER, "Photo"));
                    } catch (Exception e) {}
                } else {
                    messageForm.append(new StringItem(from + ": ", text));
                }

                int reacIdx = res.indexOf("\"reactions\":\"", idx);
                if (reacIdx != -1 && reacIdx < res.indexOf("}", idx)) {
                    int rStart = reacIdx + 13;
                    int rEnd = res.indexOf("\"", rStart);
                    if (rEnd != -1) {
                        String reactions = res.substring(rStart, rEnd);
                        if (reactions.length() > 0) {
                            messageForm.append(new StringItem(null, " [" + reactions + "]\n"));
                        }
                    }
                }
                idx = res.indexOf("}", idx);
                if (idx == -1) break;
            }
            messageForm.append(replyField);
            Displayable current = display.getCurrent();
            if (current != messageForm && current != reactionEmojiList) {
                display.setCurrent(messageForm);
            }
        } catch (Exception e) {
            showAlert("Parse Error", e.getMessage());
        }
    }

    private void sendMessage() {
        String text = replyField.getString();
        if (text.length() > 0) {
            String url = getBaseUrl() + "/send?id=" + currentChatId + "&text=" + urlEncode(text);
            httpGet(url);
            replyField.setString("");
            loadMessages(currentChatId);
        }
    }

    private void sendReaction(String emoji) {
        if (lastMsgId != 0) {
            String url = getBaseUrl() + "/react?chat_id=" + currentChatId + "&msg_id=" + lastMsgId + "&emoji=" + urlEncode(emoji);
            httpGet(url);
            loadMessages(currentChatId);
        }
    }

    private String urlEncode(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '*') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append("+");
            } else {
                String hex = Integer.toHexString(c);
                if (hex.length() == 1) sb.append("%0" + hex.toUpperCase());
                else sb.append("%" + hex.toUpperCase());
            }
        }
        return sb.toString();
    }

    private Image loadHttpImage(String url) {
        HttpConnection hc = null;
        InputStream is = null;
        try {
            hc = (HttpConnection) Connector.open(url);
            hc.setRequestProperty("bypass-tunnel-reminder", "true");
            is = hc.openInputStream();
            return Image.createImage(is);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); if (hc != null) hc.close(); } catch (Exception e) {}
        }
    }

    private String httpGet(String url) {
        HttpConnection hc = null;
        InputStream is = null;
        ByteArrayOutputStream baos = null;
        try {
            hc = (HttpConnection) Connector.open(url);
            hc.setRequestProperty("bypass-tunnel-reminder", "true");
            hc.setRequestProperty("User-Agent", "NokiaC5-00/061.005");
            int rc = hc.getResponseCode();
            if (rc != HttpConnection.HTTP_OK) {
                return "HTTP Error: " + rc;
            }
            is = hc.openInputStream();
            baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "Conn Error: " + e.getMessage() + "\nCheck IP/Firewall!\nURL: " + url;
        } finally {
            try { 
                if (baos != null) baos.close();
                if (is != null) is.close(); 
                if (hc != null) hc.close(); 
            } catch (Exception e) {}
        }
    }

    private void showAlert(String title, String text) {
        Alert alert = new Alert(title, text, null, AlertType.INFO);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert);
    }
}