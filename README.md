# VHDL Connector

A desktop tool for visually wiring VHDL entities together, similar in spirit to
Vivado's Block Design canvas. Import existing VHDL entities, drop them onto a
canvas, connect their ports (including automatic AXI4-Stream interface
bundling), add top-level ports, and export a ready-to-compile VHDL entity that
instantiates and wires everything together.

Written in plain Java + Swing, no external dependencies (JSON handling is a
small built-in reader/writer).

## Features

- **Import VHDL entities** from `.vhd`/`.vhdl` files (or recursively from a
  whole folder) — the entity name, generics, and ports are parsed straight out
  of the `entity ... is ... end entity;` declaration.
- **Canvas-based block design**: drag entities from the library onto the
  canvas, drag instances around, and connect ports by dragging from one pin to
  another.
- **Automatic AXI4-Stream grouping**: ports named `<prefix>_axis_t<signal>` or
  `<prefix>_axi_t<signal>` (e.g. `s_axis_tdata`/`s_axis_tvalid`/`s_axis_tready`/
  `s_axis_tlast`, or `m_axi_tdata`/...) are detected and collapsed into a
  single bundled interface pin (`S_AXIS`/`M_AXIS`), so you drag one wire
  instead of four. Connecting two interfaces wires every matching signal at
  once and rejects invalid master-to-master / slave-to-slave connections.
- **External (top-level) ports**: add ports for the entity that will be
  generated, with a name, direction, and VHDL type.
- **Generics**: override an instance's generic values from the canvas.
- **Direction-checked wiring**: the tool won't let you connect two outputs or
  two inputs together.
- **Save/Open projects** as a self-contained `.json` file — the full library
  (including parsed port/generic data) is embedded, so re-opening a project
  doesn't require the original `.vhd` files to still be around.
- **Export VHDL**: generates a top-level entity + architecture with component
  declarations, signal declarations, instantiations, and port maps for
  everything on the canvas.

## Requirements

- Java 17 or newer (JDK, for `javac`; developed against JDK 26).

## Build & run

```bash
./build.sh   # compiles everything into ./out
./run.sh     # builds if needed, then launches the app
```

Or manually:

```bash
find src -name "*.java" > sources.txt
javac -d out @sources.txt
java -cp out vhdlconnector.Main
```

## Usage

1. **File > Import VHDL File...** (or **Import VHDL Folder...** to recursively
   pull in every `.vhd`/`.vhdl` file under a directory). Parsed entities show
   up in the **Entity Library** panel on the left.
2. Select an entity and click **Add to Canvas** (or double-click it) to place
   an instance. Drag the instance around the canvas to position it.
3. **Wire ports together** by dragging from one pin to another:
   - Blue pins are inputs, orange pins are outputs, purple pins are inout.
   - Teal square pins are bundled AXI-Stream interfaces (`S_AXIS`/`M_AXIS`);
     hover over one to see the signals it bundles. Drag from one interface pin
     to a compatible one (master → slave) to wire all matching signals at
     once.
   - Incompatible connections (output-to-output, input-to-input, or two
     AXI-Stream interfaces with the same role) are rejected with a message in
     the status bar.
4. **Right-click** an instance, external port, or wire for more actions:
   rename an instance's instantiation label, edit its generics, edit/delete an
   external port, or delete a connection (a bundled AXI-Stream link offers a
   one-click "delete all N signals" option too).
5. **Right-click empty canvas** to add a new external (top-level) port at that
   position.
6. **Edit > Set Top Entity Name...** sets the name of the entity that will be
   generated on export.
7. **File > Save Project / Save Project As...** writes the whole design
   (library + instances + wiring) to a `.json` file. **File > Open Project...**
   reloads it later.
8. **File > Export VHDL...** writes out the generated top-level entity and
   architecture, ready to add to your VHDL sources.

Press **Delete** to remove whatever is currently selected (instance, external
port, or connection).

## Project structure

```
src/main/java/vhdlconnector/
  model/    Project data model (VhdlEntity, Port, Instance, ExternalPort,
            Connection, Project) plus AXI-Stream detection (PortGroup,
            AxiStreamDetector)
  parser/   VhdlEntityParser — extracts entity/generic/port declarations
            from VHDL source
  json/     Minimal dependency-free JSON reader/writer
  io/       ProjectIO — saves/loads a Project as JSON
  export/   VhdlExporter — generates the instantiated, wired VHDL output
  gui/      MainFrame, LibraryPanel, CanvasPanel, Dialogs — the Swing UI
```

## Limitations

- Only the `entity ... is ... end;` declaration is parsed; architecture
  bodies are ignored (not needed for instantiation/wiring).
- Connections are whole-port-to-whole-port (bus-to-bus); there's no per-bit
  wiring or width checking — mismatched vector widths are your responsibility.
- AXI-Stream grouping only applies to ports coming from imported entities;
  manually created external ports are not auto-grouped even if named the same
  way.
