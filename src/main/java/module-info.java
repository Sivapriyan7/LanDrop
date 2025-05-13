module com.example.filesharefx {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.google.gson;
    requires jdk.httpserver;
    requires java.net.http;

    opens com.example.filesharefx to javafx.fxml;
    exports com.example.filesharefx;
    requires javafx.graphics;

    // Open your model package to the Gson module
    opens com.example.filesharefx.model to com.google.gson;
}