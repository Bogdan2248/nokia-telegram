import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.*;

public class TelegramClient extends MIDlet implements CommandListener, Runnable {
    private Display display;
    private Form authForm, messageForm;
    private TextField phoneField, codeField, serverField, replyField;
    private Command sendCodeCmd, loginCmd, refreshChatsCmd, sendMsgCmd, backCmd, exitCmd, sendPhotoCmd;
    private List chatList, fileList;
    private long currentChatId;
    private long lastMsgId = 0;
    private boolean isAutoRefreshRunning = false;
    private String lastMessagesJson = "";
    private static final String RS_NAME = "tg_settings";
    private String currentPath = "file:///C:/"; // Default for Nokia C5-00

    public TelegramClient() {
        display = Display.getDisplay(this);
        
        authForm = new Form("Telegram Auth");
        phoneField = new TextField("Phone (+7...)", "+", 20, TextField.PHONENUMBER);
        codeField = new TextField("Code", "", 10, TextField.NUMERIC);
        serverField = new TextField("Server URL", "http://", 200, TextField.URL);
        
        loadSettings();

        sendCodeCmd = new Command("Send Code", Command.OK, 1);
        loginCmd = new Command("Login", Command.OK, 2);
        refreshChatsCmd = new Command("Refresh", Command.SCREEN, 3);
        backCmd = new Command("Back", Command.BACK, 1);
        exitCmd = new Command("Exit", Command.EXIT, 4);
        sendPhotoCmd = new Command("Send Photo", Command.SCREEN, 5);
        Command settingsCmd = new Command("Settings", Command.SCREEN, 6);
        
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

        fileList = new List("Select Photo", List.IMPLICIT);
        fileList.addCommand(backCmd);
        fileList.setCommandListener(this);

        messageForm = new Form("Messages");
        replyField = new TextField("Reply", "", 100, TextField.ANY);
        sendMsgCmd = new Command("Send", Command.OK, 1);
        messageForm.addCommand(sendMsgCmd);
        messageForm.addCommand(sendPhotoCmd);
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
            new Thread() { public void run() { loadChats(); } }.start();
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
                new Thread() { public void run() { loadChats(); } }.start();
            }
        } else if (c.getLabel().equals("Settings")) {
            display.setCurrent(authForm);
        } else if (c == sendMsgCmd) {
            new Thread() { public void run() { sendMessage(); } }.start();
        } else if (c == sendPhotoCmd) {
            new Thread() { public void run() { browseFiles(currentPath); } }.start();
        } else if (d == fileList && c == List.SELECT_COMMAND) {
            String selected = fileList.getString(fileList.getSelectedIndex());
            if (selected.equals(".. [Up]")) {
                int lastSlash = currentPath.lastIndexOf('/', currentPath.length() - 2);
                if (lastSlash != -1) {
                    currentPath = currentPath.substring(0, lastSlash + 1);
                    new Thread() { public void run() { browseFiles(currentPath); } }.start();
                }
            } else if (selected.endsWith("/")) {
                currentPath += selected;
                new Thread() { public void run() { browseFiles(currentPath); } }.start();
            } else {
                final String fullPath = currentPath + selected;
                new Thread() { public void run() { sendPhoto(fullPath); } }.start();
            }
        } else if (c == backCmd) {
            if (d == fileList) {
                display.setCurrent(messageForm);
            } else if (d == messageForm) {
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
        String res = httpGet(getBaseUrl() + "/chats");
        if (res == null || res.length() == 0) {
            showAlert("Error", "Empty response from server");
            return;
        }
        if (res.startsWith("[") || res.startsWith("{")) {
            // Сохраняем индекс, чтобы не перекидывало в конец/начало
            int oldIdx = chatList.getSelectedIndex();
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
                    
                    int nextObj = res.indexOf("}", idPos);
                    if (nextObj == -1) break;
                    searchIdx = nextObj;
                }
                
                if (chatList.size() > 0) {
                    if (oldIdx >= 0 && oldIdx < chatList.size()) {
                        chatList.setSelectedIndex(oldIdx, true);
                    }
                    if (display.getCurrent() != chatList && display.getCurrent() != messageForm && display.getCurrent() != authForm) {
                        display.setCurrent(chatList);
                    }
                } else {
                    showAlert("Chats", "No chats found.");
                }
            } catch (Exception e) {
                showAlert("Parse Error", e.getMessage());
            }
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
        
        // Если сообщения не изменились, ничего не перерисовываем, чтобы не прыгал фокус
        if (res.equals(lastMessagesJson)) {
            return;
        }
        lastMessagesJson = res;
        
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
                // String reactions = getJsonValue(res, "reactions", fromPos); // Реакции удалены
                String replyTo = getJsonValue(res, "reply_to", fromPos);

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

                if ("1".equals(hasPhotoStr)) {
                    messageForm.append(new StringItem(from + ": ", displayMsg + " [Photo]"));
                    try {
                        Image img = loadHttpImage(getBaseUrl() + "/photo?chat_id=" + chatId + "&msg_id=" + msgIdStr);
                        if (img != null) messageForm.append(new ImageItem(null, img, ImageItem.LAYOUT_CENTER, "Photo"));
                    } catch (Exception e) {}
                } else {
                    messageForm.append(new StringItem(from + ": ", displayMsg));
                }

                int nextObj = res.indexOf("}", fromPos);
                if (nextObj == -1) break;
                searchIdx = nextObj;
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
            new Thread() {
                public void run() {
                    String url = getBaseUrl() + "/send?id=" + currentChatId + "&text=" + encodedText;
                    httpGet(url);
                    loadMessages(currentChatId);
                }
            }.start();
            replyField.setString("");
        }
    }

    // Удалена функция sendReaction

    private void browseFiles(String path) {
        fileList.deleteAll();
        fileList.setTitle("Path: " + path);
        try {
            FileConnection fc = (FileConnection) Connector.open(path);
            Enumeration en = fc.list();
            fileList.append(".. [Up]", null);
            while (en.hasMoreElements()) {
                String file = (String) en.nextElement();
                fileList.append(file, null);
            }
            fc.close();
            display.setCurrent(fileList);
        } catch (Exception e) {
            showAlert("File Error", "Check permissions!\n" + e.getMessage());
            // Fallback for roots
            try {
                Enumeration roots = FileSystemRegistry.listRoots();
                fileList.deleteAll();
                while (roots.hasMoreElements()) {
                    fileList.append("file:///" + roots.nextElement(), null);
                }
                display.setCurrent(fileList);
            } catch (Exception e2) {
                showAlert("Root Error", e2.getMessage());
            }
        }
    }

    private void sendPhoto(String filePath) {
        FileConnection fc = null;
        InputStream is = null;
        OutputStream os = null;
        HttpConnection hc = null;
        try {
            fc = (FileConnection) Connector.open(filePath);
            if (!fc.exists()) {
                showAlert("Error", "File not found");
                return;
            }
            is = fc.openInputStream();
            
            String url = getBaseUrl() + "/upload?id=" + currentChatId;
            hc = (HttpConnection) Connector.open(url);
            hc.setRequestMethod(HttpConnection.POST);
            hc.setRequestProperty("Content-Type", "image/jpeg"); // assume jpeg
            hc.setRequestProperty("bypass-tunnel-reminder", "true");
            
            os = hc.openOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                os.write(buf, 0, len);
            }
            os.flush();
            
            int rc = hc.getResponseCode();
            if (rc == HttpConnection.HTTP_OK) {
                display.setCurrent(messageForm);
                loadMessages(currentChatId);
            } else {
                showAlert("Upload Error", "Code: " + rc);
            }
        } catch (Exception e) {
            showAlert("Upload Error", e.getMessage());
        } finally {
            try {
                if (is != null) is.close();
                if (fc != null) fc.close();
                if (os != null) os.close();
                if (hc != null) hc.close();
            } catch (Exception e) {}
        }
    }

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
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert);
    }
}