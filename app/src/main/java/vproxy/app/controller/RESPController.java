package vproxy.app.controller;

import vproxy.app.app.Application;
import vproxy.app.app.cmd.*;
import vproxy.base.Config;
import vproxy.base.connection.NetEventLoop;
import vproxy.base.connection.ServerSock;
import vproxy.base.protocol.ProtocolServerConfig;
import vproxy.base.protocol.ProtocolServerHandler;
import vproxy.base.redis.RESPConfig;
import vproxy.base.redis.RESPProtocolHandler;
import vproxy.base.redis.application.*;
import vproxy.base.util.Callback;
import vproxy.base.util.LogType;
import vproxy.base.util.Logger;
import vproxy.base.util.Utils;
import vproxy.base.util.exception.XException;
import vproxy.vfd.IPPort;

import java.io.IOException;
import java.util.List;

public class RESPController {
    public final String alias;
    public final ServerSock server;

    public RESPController(String alias, IPPort address, byte[] password) throws IOException {
        this.alias = alias;
        if (Config.checkBind) {
            ServerSock.checkBind(address);
        }
        server = ServerSock.create(address);
        NetEventLoop loop = Application.get().controlEventLoop;
        ProtocolServerHandler.apply(loop, server,
            new ProtocolServerConfig().setInBufferSize(16384).setOutBufferSize(16384),
            new RESPProtocolHandler(new RESPConfig().setMaxParseLen(16384),
                new RESPApplicationHandler(new RESPApplicationConfig().setPassword(password),
                    new RESPControllerApplication())));
    }

    public void stop() {
        server.close();
    }
}

class RESPControllerApplication implements RESPApplication<RESPApplicationContext> {
    @Override
    public RESPApplicationContext context() {
        return new RESPApplicationContext();
    }

    @Override
    public List<RESPCommand> commands() {
        return null; // ignore
    }

    @Override
    public void handle(Object o, RESPApplicationContext respApplicationContext, Callback<Object, Throwable> cb) {
        if (o == null) {
            cb.failed(new XException("cannot accept null"));
            return;
        }
        if (o instanceof String && ((String) o).trim().isEmpty()) {
            cb.succeeded("?"); // the input is empty, do nothing
            return;
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object e : (List) o) {
                if (!(e instanceof String)) {
                    cb.failed(new XException("invalid the command format"));
                    return;
                }
                sb.append(" ");
                String s = (String) e;
                sb.append(s);
            }
            o = sb.toString();
        }
        if (!(o instanceof String)) {
            cb.failed(new XException("invalid the command format"));
            return;
        }
        String line = ((String) o).trim();
        if (line.contains("\n") || line.contains("\r")) {
            cb.failed(new XException("invalid the command format"));
            return;
        }
        if (line.equals("help") || line.equals("man")) {
            // the `help` command is trapped by redis-cli
            // so let's use man instead
            cb.succeeded(Command.helpString().split("\n"));
            return;
        }
        if (line.startsWith("man ")) {
            cb.succeeded(HelpCommand.manLine(line).split("\n"));
            return;
        }
        if (line.equals("version")) {
            // version
            cb.succeeded(Application.get().version);
            return;
        }

        boolean[] isListAction = {false}; // use array to change the variable captured by the inner class
        Callback<CmdResult, Throwable> callback = new Callback<>() {
            @Override
            protected void onSucceeded(CmdResult value) {
                if (value.processedResult == null) {
                    if (isListAction[0]) {
                        cb.succeeded(null); // for list operations just return list, maybe the command wants to return so
                    } else {
                        cb.succeeded("OK"); // redis usually returns OK response when something is done
                    }
                } else {
                    cb.succeeded(value.processedResult);
                }
            }

            @Override
            protected void onFailed(Throwable err) {
                cb.failed(err);
            }
        };

        if (SystemCommand.isSystemCommand(line)) {
            if (!SystemCommand.allowNonStdIOController) {
                cb.failed(new XException("system cmd denied in RESPController"));
                return;
            }
            SystemCommand.handleSystemCommand(line, callback);
        } else {
            Command cmd;
            try {
                cmd = Command.parseStrCmd(line);
            } catch (Exception e) {
                Logger.warn(LogType.INVALID_EXTERNAL_DATA,
                    "parse cmd failed! " + Utils.formatErr(e) + " ... type `help` to show the help message");
                cb.failed(e); // callback failed
                return;
            }
            isListAction[0] = cmd.action == Action.l || cmd.action == Action.L;
            cmd.run(callback);
        }
    }
}
