import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.file.*;
import java.util.*;
import com.sun.net.httpserver.*;

public class ParkingTower {
    static Tower tower;   // created from the dashboard

    public static void main(String[] args) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(8080), 0);

        s.createContext("/", ex -> send(ex, 200, "text/html", Files.readString(Path.of("dashboard.html"))));

        s.createContext("/state", ex -> send(ex, 200, "application/json",
                tower == null ? "{\"floors\":0,\"perFloor\":0,\"capacity\":0,\"parked\":0,\"slots\":[],\"rates\":" + Tower.ratesJson() + "}" : tower.json()));

        s.createContext("/setup", ex -> {
            Map<String, String> q = query(ex);
            try {
                tower = new Tower(Integer.parseInt(q.get("floors")), Integer.parseInt(q.get("capacity")), "parking_log.txt");
                send(ex, 200, "text/plain", "ok");
            } catch (IllegalArgumentException e) {
                send(ex, 400, "text/plain", "Floors: 1 to 10. Total capacity: at least the floor count, at most 120.");
            }
        });

        s.createContext("/park", ex -> {
            if (!ready(ex)) return;
            Map<String, String> q = query(ex);
            String type = q.getOrDefault("type", ""), p = q.getOrDefault("plate", "");
            Vehicle v = type.equals("Bike") ? new Bike(p)
                      : type.equals("ElectricCar") ? new ElectricCar(p) : new Car(p);
            try {
                tower.park(v);
                send(ex, 200, "text/plain", "ok");
            } catch (ParkingFullException e) {
                send(ex, 409, "text/plain", e.getMessage());
            }
        });

        s.createContext("/exit", ex -> {
            if (!ready(ex)) return;
            Map<String, String> q = query(ex);
            try {
                send(ex, 200, "text/plain", tower.exit(Integer.parseInt(q.get("floor")), Integer.parseInt(q.get("slot")), Integer.parseInt(q.get("minutes"))));
            } catch (IllegalArgumentException e) {
                send(ex, 400, "text/plain", e.getMessage());
            }
        });

        s.start();
        System.out.println("Dashboard: http://localhost:8080");
    }

    static boolean ready(HttpExchange ex) throws IOException {
        if (tower != null) return true;
        send(ex, 400, "text/plain", "Create the tower first");
        return false;
    }

    static Map<String, String> query(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], URLDecoder.decode(p.length > 1 ? p[1] : "", "UTF-8"));
        }
        return m;
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}

class ParkingFullException extends Exception {
    ParkingFullException(String msg) { super(msg); }
}

interface Chargeable {
    void charge();
}

abstract class Vehicle {
    static int parkedCount = 0;          // static: vehicles currently parked
    String plate;
    int floor, slot;

    Vehicle(String plate) { this.plate = plate; }

    abstract double ratePerHour();
    String type() { return getClass().getSimpleName(); }
}

class Car extends Vehicle {
    Car(String plate) { super(plate); }
    double ratePerHour() { return 40; }
}

class Bike extends Vehicle {
    Bike(String plate) { super(plate); }
    double ratePerHour() { return 20; }
}

class ElectricCar extends Vehicle implements Chargeable {
    ElectricCar(String plate) { super(plate); }
    double ratePerHour() { return 60; }
    public void charge() { System.out.println(plate + " is charging"); }
}

class Tower {
    private final Vehicle[][] slots;     // [floor][slot], null = free (floors differ by at most one slot)
    private final String logFile;
    private final int capacity;

    Tower(int floors, int capacity, String logFile) {
        if (floors < 1 || floors > 10 || capacity < floors || capacity > 120)
            throw new IllegalArgumentException("size out of range");
        slots = new Vehicle[floors][];
        for (int f = 0; f < floors; f++)
            slots[f] = new Vehicle[capacity / floors + (f < capacity % floors ? 1 : 0)];
        this.capacity = capacity;
        this.logFile = logFile;
        Vehicle.parkedCount = 0;
    }

    // Nearest free slot = lowest floor, then lowest slot (entrance at floor 0, slot 0).
    void park(Vehicle v) throws ParkingFullException, IOException {
        for (int f = 0; f < slots.length; f++) {
            for (int s = 0; s < slots[f].length; s++) {
                if (slots[f][s] == null) {
                    slots[f][s] = v;
                    v.floor = f;
                    v.slot = s;
                    Vehicle.parkedCount++;
                    log("ENTRY," + v.plate + "," + v.type() + ",F" + (f + 1) + "S" + s);
                    if (v instanceof Chargeable) ((Chargeable) v).charge();
                    return;
                }
            }
        }
        throw new ParkingFullException("Parking full: no free slot for " + v.plate);
    }

    // Returns the receipt. Exits the vehicle in the given floor (0-based) and slot, so duplicates cannot clash.
    String exit(int f, int s, int minutes) throws IOException {
        if (f < 0 || f >= slots.length || s < 0 || s >= slots[f].length || slots[f][s] == null)
            throw new IllegalArgumentException("No vehicle in that slot");
        Vehicle v = slots[f][s];
        slots[f][s] = null;
        Vehicle.parkedCount--;
        log("EXIT," + v.plate + "," + v.type() + ",F" + (f + 1) + "S" + s);
        return receipt(v, minutes);
    }

    // Fee uses a cast to round up: (int) truncates, then add 1.
    // Wrong whenever the stay is an exact multiple of 60 minutes (120 min bills 3 h, not 2 h).
    static int billedHours(int minutes) {
        return (int) (minutes / 60.0) + 1;
    }

    static String ratesJson() {
        return String.format(Locale.US, "{\"Car\":%.2f,\"Bike\":%.2f,\"ElectricCar\":%.2f}",
                new Car("").ratePerHour(), new Bike("").ratePerHour(), new ElectricCar("").ratePerHour());
    }

    private String receipt(Vehicle v, int minutes) {
        int hours = billedHours(minutes);
        StringBuffer sb = new StringBuffer();
        sb.append("------ RECEIPT ------\n");
        sb.append(String.format("%-10s: %s%n", "Plate", v.plate));
        sb.append(String.format("%-10s: %s%n", "Type", v.type()));
        sb.append(String.format("%-10s: F%d S%d%n", "Slot", v.floor + 1, v.slot));
        sb.append(String.format("%-10s: %d min%n", "Parked", minutes));
        sb.append(String.format("%-10s: %d h x %.2f%n", "Billed", hours, v.ratePerHour()));
        sb.append(String.format("%-10s: %.2f%s%n", "TOTAL", hours * v.ratePerHour(),
                minutes % 60 == 0 ? " (wrong)" : ""));
        sb.append("---------------------");
        return sb.toString();
    }

    String json() {
        StringBuffer sb = new StringBuffer("{\"floors\":" + slots.length + ",\"perFloor\":" + slots[0].length
                + ",\"capacity\":" + capacity + ",\"parked\":" + Vehicle.parkedCount + ",\"slots\":[");
        for (int f = 0; f < slots.length; f++) {
            sb.append(f > 0 ? "," : "").append("[");
            for (int s = 0; s < slots[f].length; s++) {
                Vehicle v = slots[f][s];
                sb.append(s > 0 ? "," : "");
                sb.append(v == null ? "null" : "{\"plate\":\"" + v.plate.replace("\\", "").replace("\"", "")
                        + "\",\"type\":\"" + v.type() + "\"}");
            }
            sb.append("]");
        }
        return sb.append("],\"rates\":" + ratesJson() + "}").toString();
    }

    private void log(String line) throws IOException {
        try (FileWriter fw = new FileWriter(logFile, true)) {   // true = append
            fw.write(line + "\n");
        }
    }
}
