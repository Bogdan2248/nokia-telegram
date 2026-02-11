import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;

// ---Создал Bogdan2248---
public class TelegramClient extends MIDlet implements CommandListener {
    private Display display;
    private Form authForm;
    private TextField serverField;
    private Command exitCmd, nextCmd;

    public TelegramClient() {
        display = Display.getDisplay(this);
        exitCmd = new Command("Выход", Command.EXIT, 1);
        nextCmd = new Command("Далее", Command.OK, 2);
        
        authForm = new Form("Telegram");
        serverField = new TextField("Сервер:", "http://", 100, TextField.URL);
        
        authForm.append("Если вы видите это, значит приложение запустилось!");
        authForm.append(serverField);
        authForm.addCommand(exitCmd);
        authForm.addCommand(nextCmd);
        authForm.setCommandListener(this);
    }

    protected void startApp() {
        display.setCurrent(authForm);
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {}

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            notifyDestroyed();
        } else if (c == nextCmd) {
            authForm.append("\nКнопка работает!");
        }
    }
}