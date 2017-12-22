/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package jini;

import java.lang.reflect.Field;
import java.rmi.RemoteException;
import java.util.Collection;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Accordion;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import net.jini.core.entry.Entry;
import net.jini.core.lookup.ServiceID;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceRegistrar;

class Util {

    // constant(s)
    private static final String GROUP = "group";
    private static final String SERVICE_ID = "Service ID";

    static boolean inArrayById(ServiceItem[] items, ServiceItem item) {

        for (ServiceItem i : items)
            if (i.serviceID.equals(item.serviceID))
                return true;

        return false;
    }

    static boolean inCollectionById(Collection<ServiceItem> collection, ServiceItem item) {

        for (ServiceItem i : collection)
            if (i.serviceID.equals(item.serviceID))
                return true;

        return false;
    }

    static void showItem(ServiceItem item, BorderPane pane) {

        ObservableList<Row> rows = FXCollections.observableArrayList();

        rows.add(new Row(SERVICE_ID, item.serviceID.toString()));

        try {
            for (Entry entry : item.attributeSets) {

                Class<?> klass = entry.getClass();
                String name = klass.getSimpleName();

                for (Field field : klass.getFields()) {
                    Row row = new Row(name + "#" + field.getName(), field.get(entry));
                    rows.add(row);
                }
            }

            TableView<Row> table = getTable();
            table.setItems(rows);

            pane.setCenter(table);
            
        } catch (IllegalAccessException e) {}
    }

    static void showRegistrar(ServiceRegistrar registrar, BorderPane pane) {

        ObservableList<Row> rows = FXCollections.observableArrayList();

        rows.add(new Row(SERVICE_ID, registrar.getServiceID().toString()));

        try {
            for (String group : registrar.getGroups()) {
                group = group.equals("") ? Djinn.ALL_GROUPS : group;
                rows.add(new Row(GROUP, group));
            }

        } catch (RemoteException e) {}

        TableView<Row> table = getTable();
        table.setItems(rows);

        pane.setCenter(table);
    }

    static TitledPane toAccordionPane(String group, Accordion accordion) {

        List<TitledPane> panes = accordion.getPanes();

        for (int i = 0; i < panes.size(); ++i) {
            TitledPane pane = panes.get(i);
            if (pane.getText().equals(group))
                return pane;
        }

        throw new IllegalStateException();
    }

    static TreeItem toRegistrarItem(ServiceID id, TreeView tree) {

        List children = tree.getRoot().getChildren();
        TreeItem item = null;

        for (int i = 0; i < children.size(); ++i) {

            Object child = children.get(i);
            if (!(child instanceof TreeItem))
                continue;

            item = (TreeItem) child;

            Object value = item.getValue();
            if (!(value instanceof Djinn.Wrapper))
                continue;

            Object registrar = ((Djinn.Wrapper) value).object;
            if (!(registrar instanceof ServiceRegistrar))
                continue;

            if (((ServiceRegistrar) registrar).getServiceID().equals(id))
                break;
        }

        return item;
    }

    private static TableView<Row> getTable() {

        TableColumn<Row, String> names = new TableColumn<>("Name");
        names.setCellValueFactory(new PropertyValueFactory<Row, String>("Name"));

        TableColumn<Row, String> values = new TableColumn<>("Value");
        values.setCellValueFactory(new PropertyValueFactory<Row, String>("Value"));

        TableView<Row> table = new TableView<>();
        table.getColumns().addAll(names, values);

        return table;
    }

    public static class Row {

        // type(s)
        private Object value;
        private String name;

        private Row(String name, Object value) {
            super();
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public Object getValue() {
            return value;
        }

        // Object
        @Override
        public String toString() {
            return name + ": " + value.toString();
        }
    }
}
