// ---Создал Bogdan2248---
import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.*;

public class TelegramClient extends MIDlet implements CommandListener, Runnable {
    private Display display;
    private Form authForm, messageForm;
    private TextField phoneField, codeField, serverField, replyField;
    private Command sendCodeCmd, loginCmd, refreshChatsCmd, sendMsgCmd, backCmd, exitCmd;
    private List chatList;
    private long currentChatId;
    private long lastMsgId = 0;
    private boolean isAutoRefreshRunning = false;
    private String lastMessagesJson = "";
    private static final String RS_NAME = "tg_settings";

    public TelegramClient() {
        display = Display.getDisplay(this);
        
        authForm = new Form("Telegram Login");
        phoneField = new TextField("Phone Number:", "+", 20, TextField.PHONENUMBER);
        codeField = new TextField("Auth Code:", "", 10, TextField.NUMERIC);
        serverField = new TextField("Server URL:", "http://", 500, TextField.URL);
        
        loadSettings();

        sendCodeCmd = new Command("Send Code", Command.OK, 1);
        loginCmd = new Command("Login", Command.OK, 2);
        refreshChatsCmd = new Command("Refresh", Command.SCREEN, 3);
        backCmd = new Command("Back", Command.BACK, 1);
        exitCmd = new Command("Exit", Command.EXIT, 4);
        Command settingsCmd = new Command("Settings", Command.SCREEN, 5);
        
        authForm.append(new StringItem(null, "--- Connection ---"));
        authForm.append(serverField);
        authForm.append(new StringItem(null, "--- Authentication ---"));
        authForm.append(phoneField);
        authForm.append(codeField);
        
        authForm.addCommand(sendCodeCmd);
        authForm.addCommand(loginCmd);
        authForm.addCommand(refreshChatsCmd);
        authForm.addCommand(exitCmd);
        authForm.setCommandListener(this);

        chatList = new List("My Telegram Chats", List.IMPLICIT);
        chatList.addCommand(refreshChatsCmd);
        chatList.addCommand(settingsCmd);
        chatList.addCommand(exitCmd);
        chatList.setCommandListener(this);

        messageForm = new Form("Chat");
        replyField = new TextField("Message:", "", 1000, TextField.ANY);
        sendMsgCmd = new Command("Send", Command.OK, 1);
        messageForm.addCommand(sendMsgCmd);
        messageForm.addCommand(backCmd);
        messageForm.addCommand(settingsCmd);
        messageForm.addCommand(exitCmd);
        messageForm.setCommandListener(this);
    }

    private void loadSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RS_NAME, true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                serverField.setString(new String(b));
            }
        } catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    private void saveSettings() {
        RecordStore rs = null;
        try {
            RecordStore.deleteRecordStore(RS_NAME);
            rs = RecordStore.openRecordStore(RS_NAME, true);
            byte[] b = serverField.getString().getBytes();
            rs.addRecord(b, 0, b.length);
        } catch (Exception e) {}
        finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    protected void startApp() {
        if (serverField.getString().length() > 8) {
            display.setCurrent(chatList);
            loadChats();
        } else {
            display.setCurrent(authForm);
        }
        
        if (!isAutoRefreshRunning) {
            isAutoRefreshRunning = true;
            new Thread(this).start();
        }
    }

    public void run() {
        while (isAutoRefreshRunning) {
            try {
                Thread.sleep(10000);
                if (display.getCurrent() == messageForm) {
                    loadMessages(currentChatId);
                }
            } catch (Exception e) {}
        }
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == sendCodeCmd) {
            saveSettings();
            new Thread() { public void run() { sendCode(); } }.start();
        } else if (c == loginCmd) {
            saveSettings();
            new Thread() { public void run() { login(); } }.start();
        } else if (c == refreshChatsCmd || (c == List.SELECT_COMMAND && d == chatList)) {
            if (d == chatList && c == List.SELECT_COMMAND) {
                String selected = chatList.getString(chatList.getSelectedIndex());
                int start = selected.lastIndexOf('[') + 1;
                int end = selected.lastIndexOf(']');
                if (start > 0 && end > start) {
                    currentChatId = Long.parseLong(selected.substring(start, end));
                    lastMessagesJson = ""; // Reset for new chat
                    new Thread() { public void run() { loadMessages(currentChatId); } }.start();
                }
            } else {
                loadChats();
            }
        } else if (c.getLabel().equals("Settings")) {
            display.setCurrent(authForm);
        } else if (c == sendMsgCmd) {
            new Thread() { public void run() { sendMessage(); } }.start();
        } else if (c == backCmd) {
            if (d == messageForm) {
                display.setCurrent(chatList);
            } else {
                display.setCurrent(authForm);
            }
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
        new Thread() {
            public void run() {
                try {
                    int oldIdx = chatList.getSelectedIndex();
                    String res = httpGet(getBaseUrl() + "/chats");
                    if (res == null) return;
                    
                    chatList.deleteAll();
                    Vector objects = extractTopLevelObjects(res);
                    for (int i = 0; i < objects.size(); i++) {
                        String obj = (String) objects.elementAt(i);
                        String name = getJsonValue(obj, "name", 0);
                        String id = getJsonValue(obj, "id", 0);
                        String unread = getJsonValue(obj, "unread", 0);
                        
                        if (name != null) {
                            name = decodeUnicode(name);
                            String label = name + (unread != null && !"0".equals(unread) ? " [" + unread + "]" : "") + (id != null ? " [" + id + "]" : "");
                            chatList.append(label, null);
                        }
                    }
                    
                    if (oldIdx != -1 && oldIdx < chatList.size()) {
                        chatList.setSelectedIndex(oldIdx, true);
                    }
                    
                    if (display.getCurrent() != chatList && display.getCurrent() != authForm) {
                        display.setCurrent(chatList);
                    }
                } catch (Exception e) {
                    showAlert("Load Error", e.getMessage());
                }
            }
        }.start();
    }

    private Vector extractTopLevelObjects(String jsonArray) {
        Vector objects = new Vector();
        if (jsonArray == null) return objects;
        boolean inString = false;
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < jsonArray.length(); i++) {
            char ch = jsonArray.charAt(i);
            if (ch == '\"') {
                int backslashCount = 0;
                for (int j = i - 1; j >= 0 && jsonArray.charAt(j) == '\\'; j--) backslashCount++;
                if ((backslashCount % 2) == 0) inString = !inString;
            }
            if (inString) continue;
            if (ch == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && objStart != -1) {
                    objects.addElement(jsonArray.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return objects;
    }

    private String getJsonValue(String json, String key, int startIdx) {
        String searchKey = "\"" + key + "\":";
        int keyIdx = json.indexOf(searchKey, startIdx);
        if (keyIdx == -1) return null;
        int valStart = keyIdx + searchKey.length();
        while (valStart < json.length() && json.charAt(valStart) == ' ') {
            valStart++;
        }

        if (valStart >= json.length()) return null;

        boolean isString = json.charAt(valStart) == '\"';
        if (isString) valStart++;
        int valEnd = -1;

        if (isString) {
            for (int current = valStart; current < json.length(); current++) {
                if (json.charAt(current) == '\"') {
                    int backslashCount = 0;
                    for (int i = current - 1; i >= valStart && json.charAt(i) == '\\'; i--) {
                        backslashCount++;
                    }
                    if ((backslashCount % 2) == 0) {
                        valEnd = current;
                        break;
                    }
                }
            }
        } else {
            valEnd = json.indexOf(",", valStart);
            int endObj = json.indexOf("}", valStart);
            if (valEnd == -1 || (endObj != -1 && endObj < valEnd)) valEnd = endObj;
        }
        
        if (valEnd == -1) return null;
        String val = json.substring(valStart, valEnd);
        // Если это строка, убираем экранирование кавычек
        if (isString && val.indexOf("\\\"") != -1) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < val.length(); i++) {
                char c = val.charAt(i);
                if (c == '\\' && i + 1 < val.length() && val.charAt(i + 1) == '\"') {
                    sb.append('\"');
                    i++;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return val;
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
        
        // Если сообщения не изменились, ничего не перерисовываем, чтобы не прыгал фокус
        if (res.equals(lastMessagesJson)) {
            return;
        }
        lastMessagesJson = res;
        
        messageForm.deleteAll();
        try {
            Vector objects = extractTopLevelObjects(res);
            for (int idx = 0; idx < objects.size(); idx++) {
                String obj = (String) objects.elementAt(idx);
                String from = getJsonValue(obj, "from", 0);
                if (from == null) continue;
                String text = getJsonValue(obj, "text", 0);
                String msgIdStr = getJsonValue(obj, "id", 0);
                String hasPhotoStr = getJsonValue(obj, "has_photo", 0);
                // String reactions = getJsonValue(res, "reactions", fromPos); // Реакции удалены
                String replyTo = getJsonValue(obj, "reply_to", 0);

                from = decodeUnicode(from);
                text = decodeUnicode(text);
                if (replyTo != null) replyTo = decodeUnicode(replyTo);

                String displayMsg = text;
                if (replyTo != null && replyTo.length() > 0) {
                    displayMsg = "[Re: " + replyTo + "] " + text;
                }

                if (msgIdStr != null) {
                    try { lastMsgId = Long.parseLong(msgIdStr); } catch (Exception e) {}
                }

                String prefix = from.equals("Me") ? "> " : "";
                if ("1".equals(hasPhotoStr)) {
                    messageForm.append(new StringItem(null, prefix + from + ": " + displayMsg + " [Photo]"));
                    try {
                        Image img = loadHttpImage(getBaseUrl() + "/photo?chat_id=" + chatId + "&msg_id=" + msgIdStr);
                        if (img != null) messageForm.append(new ImageItem(null, img, ImageItem.LAYOUT_CENTER, "Photo"));
                    } catch (Exception e) {}
                } else {
                    messageForm.append(new StringItem(null, prefix + from + ": " + displayMsg));
                }
                messageForm.append(new StringItem(null, "--------------------"));
            }
            messageForm.append(replyField);
            if (display.getCurrent() != messageForm) {
                display.setCurrent(messageForm);
            }
        } catch (Exception e) {
            showAlert("Parse Error", e.getMessage());
        }
    }

    private void sendMessage() {
        String text = replyField.getString();
        if (text.length() > 0) {
            final String encodedText = urlEncode(text);
            String url = getBaseUrl() + "/send?id=" + currentChatId + "&text=" + encodedText;
            httpGet(url);
            loadMessages(currentChatId);
            replyField.setString("");
        }
    }

    // Удалена функция sendReaction

    private String urlEncode(String s) {
        if (s == null) return "";
        StringBuffer sb = new StringBuffer();
        try {
            byte[] utf8 = s.getBytes("UTF-8");
            for (int i = 0; i < utf8.length; i++) {
                int b = utf8[i] & 0xFF;
                if ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '-' || b == '_' || b == '.' || b == '*') {
                    sb.append((char) b);
                } else if (b == ' ') {
                    sb.append("+");
                } else {
                    sb.append("%");
                    String hex = Integer.toHexString(b);
                    if (hex.length() == 1) sb.append("0");
                    sb.append(hex.toUpperCase());
                }
            }
        } catch (Exception e) {
            // Fallback если UTF-8 не поддерживается (хотя в CLDC 1.1 должен быть)
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(c);
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
        alert.setTimeout(3000);
        Displayable next = display.getCurrent();
        if (next == null) next = authForm;
        display.setCurrent(alert, next);
    }
}
