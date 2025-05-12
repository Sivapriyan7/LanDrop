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
    // Add if you use FXML, though not in current code
    // requires javafx.fxml;

    // Require the Gson module

    // Require the HttpClient module

    // === THIS IS THE CRUCIAL PART ===
    // Open your model package to the Gson module
    opens com.example.filesharefx.model to com.google.gson;

    // You might also need to open other packages if Gson needs to serialize/deserialize
    // objects from them directly, but for now, 'model' is the one causing issues.

    // Export your main package if it contains the Application class
    // You might not need to export services if they are only used internally
    // exports com.example.filesharefx.services;
}