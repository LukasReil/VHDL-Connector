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
  of the `entity ... is ... end entity;` declaration. Re-importing over an
  existing library entity (e.g. after fixing up the source) automatically
  drops any connection left dangling by a port that got renamed or removed in
  the process, rather than leaving a stale connection that can never be drawn
  but still blocks a new wire to that pin.
- **Import Vivado IP** from an IP folder (recursively, same as the VHDL folder
  import): each core's `.vho` instantiation template — a `COMPONENT ... PORT
  (...); END COMPONENT;` block plus a port-map template, as Vivado generates
  next to a generated IP core — is parsed the same way a real entity would be.
- **Canvas-based block design**: drag entities from the library onto the
  canvas, drag instances (and external ports) around, and connect ports by
  dragging from one pin to another. Wires are auto-routed at right angles
  around instance boxes rather than drawn straight through them, and signals
  fanning out from the same source pin (e.g. a shared `clk`) share a common
  trunk instead of being drawn as separate overlapping lines.
- **Automatic AXI4-Stream grouping**: any run of ports ending in `_t<signal>`
  (`tdata`, `tvalid`, `tready`, `tlast`, `tkeep`, `tstrb`, `tid`, `tdest`,
  `tuser`) whose shared prefix contains an `axi`/`axis` token is detected and
  collapsed into a single bundled interface pin (`S_AXIS`/`M_AXIS`), so you
  drag one wire instead of several — covering plain `s_axis_tdata`, the
  `s`-less `s_axi_tdata`, and Vivado-generated IP's qualified names like
  `s_axis_a_tdata`/`s_axis_b_tdata`/`m_axis_result_tdata` (a core with more
  than one stream of the same role, correctly kept as separate interfaces
  rather than merged into one). This applies both to ports coming from
  imported entities/IP *and* to external (top-level) ports you create
  yourself. Connecting two interfaces wires every matching signal at once,
  rejects invalid master-to-master / slave-to-slave connections, and the
  whole bundle behaves as a single wire on the canvas (one thicker line, one
  "delete the whole link" action) instead of one line per underlying signal.
- **External (top-level) ports**, created via a dialog with a type-specific
  form:
  - `std_logic` — just a direction.
  - `std_logic_vector` — direction + a bit-width field.
  - `AXI4-Stream` — a role (slave/input or master/output), a data width, and
    checkboxes (with width fields where relevant) to include `TLAST`,
    `TKEEP`, `TSTRB`, `TID`, `TDEST`, `TUSER`; this generates and bundles the
    whole interface's ports at once, same as an imported AXI-Stream entity
    port.
  - `Custom...` — a free-text VHDL type, for anything not covered above.
- **Generics**: override an instance's generic values from the canvas.
- **Direction-checked wiring**: the tool won't let you connect two outputs or
  two inputs together.
- **Multi-select and copy/paste**: Ctrl+Click adds instances to a selection;
  Ctrl+C/Ctrl+V copies them (with their generics and any connection directly
  between two copied instances) and pastes new, uniquely-labeled instances.
- **Auto-Connect** (`Edit > Auto-Connect...`): pick one source signal (an
  instance's port, or an external port) and a destination port name, and it's
  wired to every enabled instance that has a port with that name — handy for
  broadcasting `clk`/`rst_n` to a whole design in one go. Instances can be
  individually excluded from the sweep; a destination pin that's already
  connected to something else is always left alone rather than rewired, so
  auto-connect can never create a multi-driven net.
- **Save/Open projects** as a self-contained `.json` file — the full library
  (including parsed port/generic data) is embedded, so re-opening a project
  doesn't require the original `.vhd` files to still be around. Each entity's
  source file is recorded *relative to the project file itself*, so a project
  stays portable: moving or sharing the project folder together with its
  `.vhd` sources (e.g. via git) keeps those references correct, rather than
  pointing at one machine's absolute file layout. Project files saved by
  older versions of this tool (with an absolute source path) still load fine.
- **Export VHDL**: generates a top-level entity + architecture (with the
  necessary `library ieee; use ieee.std_logic_1164.all; use
  ieee.numeric_std.all;` clauses) containing component declarations, signal
  declarations, instantiations, and port maps for everything on the canvas.
  Generic-dependent port widths (e.g. a port typed
  `std_logic_vector(WIDTH-1 downto 0)`) are resolved per-instance — using that
  instance's generic-map override, or the entity's own default otherwise —
  and evaluated down to a concrete literal, so the generated signal
  declarations are always valid VHDL rather than referencing an
  out-of-scope generic name. A width mismatch between two connected ports (or
  a generic with neither an override nor a default) is reported as a warning.

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
   pull in every `.vhd`/`.vhdl` file under a directory, or **Import IP
   Folder...** to recursively pull in every `.vho` instantiation template
   under a directory — e.g. Vivado's per-core `ip/<core>/<core>.vho` layout).
   Parsed entities show up in the **Entity Library** panel on the left.
2. Select an entity and click **Add to Canvas** (or double-click it) to place
   an instance. Drag the instance around the canvas to position it — wires
   attached to it reroute automatically around other instances.
3. **Click an instance to select it; Ctrl+Click additional instances to build
   a multi-selection** (Ctrl+Click a selected instance again to deselect just
   that one). **Ctrl+C** copies the selection, **Ctrl+V** pastes it as new
   instances, offset from the originals (repeated pasting cascades further
   each time). A pasted instance keeps its generics overrides and, if two
   copied instances were directly wired together, that connection is
   recreated between the two pasted copies — a connection to anything outside
   the copied set is left out, since there'd be nothing to reconnect it to.
   Instantiation labels are kept unless they'd collide with an existing one,
   in which case the copy gets a numbered suffix.
4. **Wire ports together** by dragging from one pin to another:
   - Blue pins are inputs, orange pins are outputs, purple pins are inout.
   - Teal square pins are bundled AXI-Stream interfaces (`S_AXIS`/`M_AXIS`,
     on instances and on external ports alike); hover over one to see the
     signals it bundles. Drag from one interface pin to a compatible one
     (master → slave) to wire all matching signals at once, drawn as a
     single thicker teal wire.
   - Incompatible connections (output-to-output, input-to-input, or two
     AXI-Stream interfaces with the same role) are rejected with a message in
     the status bar.
5. **Right-click** an instance, external port, or wire for more actions:
   rename an instance's instantiation label, edit its generics, edit/delete an
   external port or AXI-Stream interface, or delete a connection (deleting a
   bundled AXI-Stream link removes every signal in it at once).
6. **Right-click empty canvas** to add a new external (top-level) port at that
   position — pick its type (`std_logic`, `std_logic_vector`, `AXI4-Stream`,
   or `Custom...`) in the dialog that appears.
7. External ports can be dragged anywhere on the canvas, just like instances;
   clicking precisely on the pin tip instead starts a wire.
8. **Edit > Set Top Entity Name...** sets the name of the entity that will be
   generated on export. **Edit > Auto-Connect...** opens the broadcast-connect
   dialog described above.
9. **File > Save Project** (`Ctrl+S`) **/ Save Project As...** (`Ctrl+Shift+S`)
   writes the whole design (library + instances + wiring) to a `.json` file.
   **File > Open Project...** (`Ctrl+O`) reloads it later, and **New Project**
   (`Ctrl+N`) starts a fresh one.
10. **File > Export VHDL...** writes out the generated top-level entity and
    architecture, ready to add to your VHDL sources.

Press **Delete** to remove whatever is currently selected (instance, external
port, AXI-Stream interface, or connection/link).

## Project structure

```
src/main/java/vhdlconnector/
  model/    Project data model (VhdlEntity, Port, Instance, ExternalPort,
            Connection, Project) plus AXI-Stream detection (PortGroup,
            AxiStreamDetector)
  parser/   VhdlEntityParser — extracts entity/generic/port declarations from
            VHDL source, or from a Vivado .vho IP instantiation template's
            COMPONENT declaration
  json/     Minimal dependency-free JSON reader/writer
  io/       ProjectIO — saves/loads a Project as JSON, storing each entity's
            source .vhd path relative to the project file for portability
  export/   VhdlExporter — generates the instantiated, wired VHDL output,
            resolving generic-dependent port widths per instance
  gui/      MainFrame, LibraryPanel, CanvasPanel, Dialogs — the Swing UI;
            OrthogonalRouter — obstacle-avoiding, trunk-sharing wire routing
```

## Limitations

- Only the `entity ... is ... end;` declaration is parsed; architecture
  bodies are ignored (not needed for instantiation/wiring).
- Connections are whole-port-to-whole-port (bus-to-bus); there's no per-bit
  wiring. Width mismatches are flagged as export warnings but not prevented
  on the canvas.
- Generic substitution in port widths only evaluates plain integer arithmetic
  (`+ - * /` and parentheses) — a generic used in a more exotic expression is
  left as-is (with a warning) if it can't be reduced to a number.
