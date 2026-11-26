package com.sica.presentacion.componentes.calendar;

import com.sica.presentacion.integracion.SicaData;
import com.sica.presentacion.integracion.SicaData.Visit;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.sica.presentacion.componentes.ElementosUi.*;

public final class CalendarioVisitas extends VBox {
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", SicaData.SPANISH);
    private static final List<String> DAYS = List.of("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom");
    private final LocalDate today;
    private final Supplier<List<Visit>> source;
    private final Consumer<LocalDate> onDate;
    private final Label monthLabel = label("", "card-title");
    private final GridPane grid = new GridPane();
    private YearMonth month;
    private LocalDate selected;

    public CalendarioVisitas(LocalDate today, Supplier<List<Visit>> source, Consumer<LocalDate> onDate) {
        super(13);
        this.today = today;
        this.source = source;
        this.onDate = onDate;
        this.selected = today;
        this.month = YearMonth.from(today);
        getStyleClass().add("sica-calendar");
        grid.setHgap(4);
        grid.setVgap(4);
        for (int i = 0; i < DAYS.size(); i++) {
            ColumnConstraints column = new ColumnConstraints(25, 45, Double.MAX_VALUE);
            column.setPercentWidth(100.0 / DAYS.size());
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        getChildren().addAll(row(8, monthLabel, spacer(),
                iconButton("Mes anterior", "left", () -> changeMonth(-1)),
                iconButton("Mes siguiente", "right", () -> changeMonth(1))), grid);
        render();
    }

    public void refresh() {
        render();
        onDate.accept(selected);
    }

    private void changeMonth(int amount) {
        month = month.plusMonths(amount);
        selected = month.equals(YearMonth.from(today)) ? today : month.atDay(1);
        render();
        onDate.accept(selected);
    }

    private void render() {
        String caption = month.atDay(1).format(MONTH);
        monthLabel.setText(caption.substring(0, 1).toUpperCase(SicaData.SPANISH) + caption.substring(1));
        grid.getChildren().clear();
        for (int i = 0; i < DAYS.size(); i++) {
            Label heading = label(DAYS.get(i), "calendar-weekday");
            heading.setMaxWidth(Double.MAX_VALUE);
            heading.setAlignment(Pos.CENTER);
            grid.add(heading, i, 0);
        }
        int firstColumn = month.atDay(1).getDayOfWeek().getValue() - 1;
        List<Visit> visits = source.get();
        for (int day = 0; day < 42; day++) {
            LocalDate date = month.atDay(1).plusDays(day - firstColumn);
            Label numeral = label(Integer.toString(date.getDayOfMonth()), "day-number");
            Region dot = new Region();
            dot.getStyleClass().add("calendar-dot");
            dot.setVisible(visits.stream().anyMatch(visit -> visit.date().equals(date)));
            VBox content = new VBox(2, numeral, dot);
            content.setAlignment(Pos.CENTER);
            Button cell = new Button("", content);
            cell.setAccessibleText(date.format(SicaData.DATE) + (dot.isVisible() ? ", con visitas" : ""));
            cell.setMaxWidth(Double.MAX_VALUE);
            cell.getStyleClass().add("calendar-day");
            if (!YearMonth.from(date).equals(month)) cell.getStyleClass().add("other-month");
            if (date.equals(today)) cell.getStyleClass().add("is-today");
            if (date.equals(selected)) cell.getStyleClass().add("selected-day");
            grid.add(cell, day % 7, day / 7 + 1);
            cell.setOnAction(event -> {
                selected = date;
                month = YearMonth.from(date);
                render();
                grid.getChildren().stream().filter(node -> node.getStyleClass().contains("selected-day"))
                        .findFirst().ifPresent(node -> node.requestFocus());
                onDate.accept(date);
            });
        }
    }
}
