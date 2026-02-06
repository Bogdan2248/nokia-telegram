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
        if (res.startsWith("[") || res.startsWith("{")) {
            chatList.deleteAll();
            try {
                int searchIdx = 0;
                while (true) {
                    String id = getJsonValue(res, "id", searchIdx);
                    if (id == null) break;
                    
                    int idPos = res.indexOf("\"id\"", searchIdx);
                    String name = getJsonValue(res, "name", idPos);
                    if (name == null) name = "Unknown";
                    
                    name = decodeUnicode(name);
                    chatList.append(name + " [" + id + "]", null);
                    
                    // Сдвигаем поиск за пределы текущего объекта
                    int nextObj = res.indexOf("}", idPos);
                    if (nextObj == -1) break;
                    searchIdx = nextObj;
                }
                
                if (chatList.size() == 0) {
                    showAlert("Chats", "No chats found. Raw: " + (res.length() > 100 ? res.substring(0, 100) : res));
                } else {
                    display.setCurrent(chatList);
                }
            } catch (Exception e) {
                showAlert("Parse Error", e.getMessage() + "\nRaw: " + (res.length() > 100 ? res.substring(0, 100) : res));
            }
        } else {
            showAlert("Error", "Server error. Response: " + (res.length() > 100 ? res.substring(0, 100) : res));
        }
    }

    private String getJsonValue(String json, String key, int startIdx) {
        int keyIdx = json.indexOf("\"" + key + "\"", startIdx);
        if (keyIdx == -1) return null;
        
        int colonIdx = json.indexOf(":", keyIdx);
        if (colonIdx == -1) return null;
        
        int valStart = -1;
        int valEnd = -1;
        
        for (int i = colonIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\"') {
                valStart = i + 1;
                valEnd = json.indexOf("\"", valStart);
                break;
            } else if (Character.isDigit(c) || c == '-' || c == 't' || c == 'f') {
                valStart = i;
                for (int j = i; j < json.length(); j++) {
                    char c2 = json.charAt(j);
                    if (c2 == ',' || c2 == '}' || c2 == ' ' || c2 == ']') {
                        valEnd = j;
                        break;
                    }
                }
                break;
            }
        }
        
        if (valStart != -1 && valEnd != -1) {
            return json.substring(valStart, valEnd).trim();
        }
        return null;
    }

    private String decodeUnicode(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (idx < s.length()) {
            char c = s.charAt(idx);
            if (c == '\\' && idx + 1 < s.length()) {
                char next = s.charAt(idx + 1);
                if (next == 'u' && idx + 5 < s.length()) {
                    try {
                        String hex = s.substring(idx + 2, idx + 6);
                        int code = Integer.parseInt(hex, 16);
                        sb.append((char) code);
                        idx += 6;
                    } catch (Exception e) {
                        sb.append(c);
                        idx++;
                    }
                } else {
                    if (next == 'n') sb.append('\n');
                    else if (next == 't') sb.append('\t');
                    else if (next == '\"') sb.append('\"');
                    else if (next == '\\') sb.append('\\');
                    else sb.append(next);
                    idx += 2;
                }
            } else {
                sb.append(c);
                idx++;
            }
        }
        return sb.toString();
    }

    private void loadMessages(long chatId) {
        String res = httpGet(getBaseUrl() + "/messages?id=" + chatId);
        if (res == null || res.length() == 0 || res.startsWith("Error")) {
            showAlert("Error", "Could not load messages: " + res);
            return;
        }
        
        messageForm.deleteAll();
        try {
            int searchIdx = 0;
            while (true) {
                String from = getJsonValue(res, "from", searchIdx);
                if (from == null) break;
                
                int fromPos = res.indexOf("\"from\"", searchIdx);
                String text = getJsonValue(res, "text", fromPos);
                String msgIdStr = getJsonValue(res, "id", fromPos);
                String hasPhotoStr = getJsonValue(res, "has_photo", fromPos);
                String reactions = getJsonValue(res, "reactions", fromPos);
                String replyTo = getJsonValue(res, "reply_to", fromPos);

                from = decodeUnicode(from);
                text = decodeUnicode(text);
                if (reactions != null) reactions = decodeUnicode(reactions);
                if (replyTo != null) replyTo = decodeUnicode(replyTo);

                if (replyTo != null && replyTo.length() > 0) {
                    messageForm.append(new StringItem(null, " > Re: " + replyTo + "\n"));
                }

                if (msgIdStr != null) {
                    try { lastMsgId = Long.parseLong(msgIdStr); } catch (Exception e) {}
                }

                if ("1".equals(hasPhotoStr)) {
                    messageForm.append(new StringItem(from + ": ", text + " [Photo]"));
                    try {
                        Image img = loadHttpImage(getBaseUrl() + "/photo?chat_id=" + chatId + "&msg_id=" + msgIdStr);
                        if (img != null) messageForm.append(new ImageItem(null, img, ImageItem.LAYOUT_CENTER, "Photo"));
                    } catch (Exception e) {}
                } else {
                    messageForm.append(new StringItem(from + ": ", text));
                }

                if (reactions != null && reactions.length() > 0) {
                    messageForm.append(new StringItem(null, " [" + reactions + "]\n"));
                }

                int nextObj = res.indexOf("}", fromPos);
                if (nextObj == -1) break;
                searchIdx = nextObj;
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