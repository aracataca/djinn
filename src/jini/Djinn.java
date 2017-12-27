package jini;

import java.rmi.RemoteException;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Accordion;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.jini.core.discovery.LookupLocator;
import net.jini.core.entry.Entry;
import net.jini.core.lookup.ServiceID;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceMatches;
import net.jini.core.lookup.ServiceRegistrar;
import net.jini.core.lookup.ServiceTemplate;
import net.jini.discovery.DiscoveryEvent;
import net.jini.discovery.DiscoveryListener;
import net.jini.discovery.LookupDiscovery;
import net.jini.lookup.entry.ServiceInfo;

/**
 * Djinn comprises two loops: a scheduled loop to sense djinn activity,
 * set to run every FREQUENCY seconds; and the JavaFX event loop accessed via the
 * <code>Platform.runLater(..)</code> method, which animates the screen controls. Events that
 * are detected by the scheduled loop, whose body resides in the <code>Djinn#run()</code>
 * method, are wrapped in Command objects, and queued onto the JavaFX event loop
 * to be run later, to affect screen control changes.
 * 
 * @author pickup
 */
public class Djinn extends Application implements ChangeListener, DiscoveryListener, Runnable {

    // constant(s)
    private static final long FREQUENCY = 16L;                                  // Throttle polling here.
    private static final long HIATUS = 2L;                                      // Initial pause before polling start.

    public static final String ALL_GROUPS = "ALL GROUPS";                       // Literal used instead of "".
    private static final String SERVICES = "Services";
    private static final String SCHEDULED = "scheduled";                        // Thread name.
    private static final String TITLE = "Djinn";

    // type(s)
    private Accordion accordion;
    private BorderPane pane;
    private Deque<Event> deque = new ConcurrentLinkedDeque<>();                 // Competition between DiscoveryListener & 'scheduled'.
    private LookupDiscovery discovery;
    private Map<ServiceID, ServiceRegistrar> registrars = new HashMap<>();
    private Map<ServiceID, Map<ServiceID, ServiceItem>> services = new HashMap<>();
    private ScheduledExecutorService scheduled;
    private TreeView tree;

    public Djinn() {
        super();
    }

    // Application
    @Override
    public void init() throws Exception {

        scheduled = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {

            // ThreadFactory
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(runnable, SCHEDULED);
            }
        });

        discovery = new LookupDiscovery(LookupDiscovery.NO_GROUPS);

        discovery.addDiscoveryListener((DiscoveryListener) this);
        discovery.setGroups(LookupDiscovery.ALL_GROUPS);
    }

    // Application
    @Override
    public void start(Stage stage) throws Exception {

        pane = new BorderPane();

        TreeItem root = new TreeItem(SERVICES);
        root.setExpanded(true);

        tree = new TreeView(root);
        tree.getSelectionModel().selectedItemProperty().addListener((ChangeListener) this);

        pane.setLeft(tree);

        accordion = new Accordion();
        pane.setRight(accordion);

        Scene scene = new Scene(pane, 800, 400);

        stage.setScene(scene);
        stage.setTitle(TITLE);

        stage.show();

        scheduled.schedule((Runnable) this, HIATUS, TimeUnit.SECONDS);
    }

    // Application
    @Override
    public void stop() throws Exception {

        discovery.terminate();                                                  // This leaves parked threads.
        scheduled.shutdownNow();                                                // Lose queued tasks.

        System.exit(0);                                                         // Inelegant. See terminate comment.
    }

    // ChangeListener
    @Override
    public void changed(ObservableValue observable, Object last, Object next) {

        if (next == null) {
            Platform.runLater((Runnable) new Changed());
            return;
        }

        if (!(next instanceof TreeItem))
            return;

        TreeItem item = (TreeItem) next;

        if (item.getParent() == null) {
            Platform.runLater((Runnable) new Changed());
            return;
        }

        Object value = item.getValue();
        if (!(value instanceof Wrapper))
            return;

        Wrapper wrapper = (Wrapper) value;
        Object object = wrapper.object;

        Platform.runLater((Runnable) new Changed(object));
    }

    // DiscoveryListener
    @Override
    public void discarded(DiscoveryEvent event) {
        System.out.println("discarded");
    }

    // DiscoveryListener
    @Override
    public void discovered(DiscoveryEvent event) {

        for (ServiceRegistrar registrar : event.getRegistrars())
            deque.add(new Event(Event.DISCOVERED, registrar));

        System.out.println("discovered");
    }

    /**
     * This is the heart of the event generator, that is run every FREQUENCY seconds.
     * It generates Discovered and Discarded events for registrar discovery and
     * partition, and Added and Removed events for service discovery and their
     * partition. <code>Djinn#run()</code> accepts <code>Djinn$Event</code> objects generated from
     * <code>LookupDiscovery(..)</code> events, that are fed in via a concurrent deque. It then
     * checks for any services associated with the new registrar via a call to
     * <code>ServiceRegistrar#lookup(..)</code>, before executing the major part of the run()
     * method.
     */
    // Runnable
    @Override
    public void run() {

        while (!deque.isEmpty())
            try {
                Event event = deque.remove();

                ServiceRegistrar registrar = event.registrar;
                ServiceID id = registrar.getServiceID();

                if (registrars.containsKey(id))
                    continue;

                Platform.runLater((Runnable) new Discovered(registrar));

                registrars.put(id, registrar);

                // RemoteException
                ServiceMatches matches = registrar.lookup(new ServiceTemplate(null, null, null), Integer.MAX_VALUE);
                for (ServiceItem item : matches.items) {

                    Platform.runLater((Runnable) new Added(registrar, item));

                    if (!services.containsKey(id))
                        services.put(id, new HashMap<ServiceID, ServiceItem>());

                    services.get(id).put(item.serviceID, item);
                }

            } catch (RemoteException e) {}

        /*
         * The "major part". This polls each registrar. This is ugly but necessary
         * in order to find out exactly where a service is registered, given that
         * a single service can be registered with N registrars.
         */
        boolean collapse = false;

        Map<ServiceID, ServiceItem> map = null;
        ServiceRegistrar registrar = null;

        Iterator<ServiceRegistrar> iterator = registrars.values().iterator();

        while (iterator.hasNext())
            try {
                registrar = iterator.next();
                map = services.get(registrar.getServiceID());

                // RemoteException
                ServiceMatches matches = registrar.lookup(new ServiceTemplate(null, null, null), Integer.MAX_VALUE);

                for (ServiceItem item : matches.items) {

                    if (Util.inCollectionById(map.values(), item))
                        continue;

                    Platform.runLater((Runnable) new Added(registrar, item));
                    map.put(item.serviceID, item);

                    collapse = true;
                }

                Iterator<ServiceItem> i = map.values().iterator();

                while (i.hasNext()) {

                    ServiceItem item = i.next();

                    if (Util.inArrayById(matches.items, item))
                        continue;

                    Platform.runLater((Runnable) new Removed(registrar, item));
                    i.remove();

                    collapse = true;
                }

            } catch (RemoteException e) {

                for (ServiceItem item : map.values())
                    Platform.runLater((Runnable) new Removed(registrar, item));

                services.remove(registrar.getServiceID());

                Platform.runLater((Runnable) new Discarded(registrar));
                iterator.remove();

                collapse = true;

            } catch (Exception e) {}

        /*
         * Owing to the JavaFX bug that selects a random tree item when
         * the tree is modified, lengthened or shortened, Djinn collapses
         * the tree instead of showing random item data, in the central pane,
         * out of sync with the item shown selected in the tree.
         */
        //if (collapse)
        //    Platform.runLater((Runnable) new Collapse());

        scheduled.schedule((Runnable) this, FREQUENCY, TimeUnit.SECONDS);
    }

    public static final void main(String[] args) {
        Application.launch(args);
    }

    private class Added implements Runnable {

        // type(s)
        private ServiceItem item;
        private ServiceRegistrar registrar;

        private Added(ServiceRegistrar registrar, ServiceItem item) {
            super();
            this.registrar = registrar;
            this.item = item;
        }

        // Runnable
        @Override
        public void run() {

            String label = null;

            for (Entry entry : item.attributeSets)
                if (entry instanceof ServiceInfo) {
                    label = ((ServiceInfo) entry).name;
                    break;
                }

            Wrapper value = new Wrapper(this.item, (label == null) ? item.serviceID.toString() : label);

            TreeItem i = new TreeItem();
            i.setValue(value);

            Util.toRegistrarItem(registrar.getServiceID(), tree).getChildren().add(i);
        }
    }

    private class Changed implements Runnable {

        // type(s)
        private Object object;

        private Changed() {
            super();
        }

        private Changed(Object object) {
            this();
            this.object = object;
        }

        // Runnable
        @Override
        public void run() {

            if (object == null) {
                Node node = pane.getCenter();
                if (node != null)
                    node.setVisible(false);
            }

            if (object instanceof ServiceItem) {
                ServiceItem item = (ServiceItem) object;
                Util.showItem(item, pane);
            }

            if (object instanceof ServiceRegistrar) {
                ServiceRegistrar registrar = (ServiceRegistrar) object;
                Util.showRegistrar(registrar, pane);
            }
        }
    }

    private class Collapse implements Runnable {

        private Collapse() {
            super();
        }

        // Runnable
        @Override
        public void run() {

            tree.getRoot().setExpanded(false);

            /*
             * Workaround. Djinn#changed(..) seems to be called during or after
             * TreeItem#setExpanded(..) is executed. The #changed(..) call, which
             * consequently calls Djinn$Changed, erroneously, can be remedied by
             * a further null argument call to Djinn$Changed, as follows...
             */
            Platform.runLater((Runnable) new Changed());
        }
    }

    private class Discarded implements Runnable {

        // type(s)
        private ServiceRegistrar registrar;

        private Discarded(ServiceRegistrar registrar) {
            super();
            this.registrar = registrar;
        }

        // Runnable
        @Override
        public void run() {

            List<TitledPane> panes = accordion.getPanes();

            for (int i = 0; i < panes.size(); ++i) {

                TitledPane pane = panes.get(i);
                VBox box = (VBox) pane.getContent();

                List<Node> children = box.getChildren();

                for (Node child : children)
                    if (child.getUserData().equals(registrar.getServiceID())) {
                        children.remove(child);
                        break;
                    }
            }

            Iterator<TitledPane> iterator = panes.iterator();

            while (iterator.hasNext()) {

                TitledPane pane = iterator.next();
                VBox box = (VBox) pane.getContent();

                if (box.getChildren().isEmpty())
                    iterator.remove();
            }

            List children = tree.getRoot().getChildren();

            for (int i = 0; i < children.size(); ++i) {

                Object child = children.get(i);
                if (!(child instanceof TreeItem))
                    continue;

                Object value = ((TreeItem) child).getValue();
                if (!(value instanceof Wrapper))
                    continue;

                Object r = ((Wrapper) value).object;
                if (!(r instanceof ServiceRegistrar))
                    continue;

                if (((ServiceRegistrar) r).getServiceID().equals(registrar.getServiceID()))
                    children.remove(child);
            }
        }
    }

    private class Discovered implements Runnable {

        // type(s)
        private ServiceRegistrar registrar;

        private Discovered(ServiceRegistrar registrar) {
            super();
            this.registrar = registrar;
        }

        // Runnable
        @Override
        public void run() {

            List children = tree.getRoot().getChildren();

            try {
                LookupLocator locator = registrar.getLocator();
                String label = locator.getHost() + ":" + locator.getPort();

                TreeItem child = new TreeItem();

                child.setExpanded(true);
                child.setValue(new Wrapper(registrar, label));

                children.add(child);

                List<TitledPane> panes = accordion.getPanes();
                Set<String> groups = new HashSet<>();

                for (int i = 0; i < panes.size(); ++i) {
                    TitledPane pane = panes.get(i);
                    groups.add(pane.getText());
                }

                for (String group : registrar.getGroups()) {

                    group = group.equals("") ? ALL_GROUPS : group;

                    if (!groups.contains(group)) {

                        VBox box = new VBox(10);
                        box.setPadding(new Insets(10));

                        TitledPane pane = new TitledPane(group, box);
                        panes.add(pane);
                    }

                    Node node = new Label(label);
                    node.setUserData(registrar.getServiceID());

                    TitledPane pane = Util.toAccordionPane(group, accordion);
                    VBox box = (VBox) pane.getContent();

                    box.getChildren().add(node);
                }

            } catch (RemoteException e) {
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class Removed implements Runnable {

        // type(s)
        private ServiceItem item;
        private ServiceRegistrar registrar;

        private Removed(ServiceRegistrar registrar, ServiceItem item) {
            super();
            this.registrar = registrar;
            this.item = item;
        }

        // Runnable
        @Override
        public void run() {

            TreeItem item = Util.toRegistrarItem(registrar.getServiceID(), tree);
            List children = item.getChildren();

            for (int i = 0; i < children.size(); ++i) {

                Object child = children.get(i);
                if (!(child instanceof TreeItem))
                    continue;

                Object value = ((TreeItem) child).getValue();
                if (!(value instanceof Wrapper))
                    continue;

                Object s = ((Wrapper) value).object;
                if (!(s instanceof ServiceItem))
                    continue;

                if (((ServiceItem) s).serviceID.equals(this.item.serviceID)) {
                    children.remove(child);
                    break;
                }
            }
        }
    }

    private static class Event {

        // constant(s)
        private static final String DISCARDED = "discarded";                    // Discards are ignored.
        private static final String DISCOVERED = "discovered";

        // type(s)
        private ServiceRegistrar registrar;
        private String type;

        private Event(String type, ServiceRegistrar registrar) {
            super();
            this.type = type;
            this.registrar = registrar;
        }
    }

    static class Wrapper {

        // type(s)
        Object object;
        private String label;

        private Wrapper(Object object, String label) {
            super();
            this.object = object;
            this.label = label;
        }

        // Object
        @Override
        public String toString() {
            return label;
        }
    }
}
