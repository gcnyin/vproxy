package vproxy.app.app.cmd.handle.resource;

import vproxy.app.app.Application;
import vproxy.app.app.cmd.Command;
import vproxy.app.app.cmd.Resource;
import vproxy.base.component.elgroup.EventLoopGroup;
import vproxy.base.component.elgroup.EventLoopWrapper;
import vproxy.base.util.exception.XException;

import java.util.List;

public class EventLoopHandle {
    private EventLoopHandle() {
    }

    public static EventLoopWrapper get(Resource resource) throws Exception {
        String groupName = resource.parentResource.alias;
        return Application.get().eventLoopGroupHolder.get(groupName).get(resource.alias);
    }

    public static List<String> names(Resource targetResource) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(targetResource);
        return g.names();
    }

    public static void add(Command cmd) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(cmd.prepositionResource);
        if (Application.isDefaultEventLoopGroupName(g.alias))
            throw new XException("cannot modify the default event loop group " + g.alias);
        g.add(cmd.resource.alias);
    }

    public static void remove(Command cmd) throws Exception {
        EventLoopGroup g = EventLoopGroupHandle.get(cmd.prepositionResource);
        if (Application.isDefaultEventLoopGroupName(g.alias))
            throw new XException("cannot modify the default event loop group " + g.alias);
        g.remove(cmd.resource.alias);
    }
}
