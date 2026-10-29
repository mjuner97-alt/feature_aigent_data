# TXT 转二维码桌面工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Windows-friendly GUI that splits a large TXT into UTF-8-safe chunks and saves ordered QR-code PNGs.

**Architecture:** Pure core module handles chunking, QR rendering, and manifest writing. Tkinter GUI handles file dialogs and progress. PyInstaller script packages the GUI as one executable.

**Tech Stack:** Python 3.10+, Tkinter, qrcode[pil], Pillow, pytest, PyInstaller.

## Global Constraints

- Default chunk size is 1000 UTF-8 bytes.
- Text is never split in the middle of a UTF-8 character.
- Output is a timestamped subdirectory under the selected destination.
- No history-changing Git commands.

### Task 1: Core chunking and output
**Files:** Create `tools/txt_qr/qr_core.py`, `tools/txt_qr/tests/test_qr_core.py`.
- [ ] Add tests for multibyte-safe chunking, empty input rejection, and manifest/output creation.
- [ ] Run pytest and observe failure because module is absent.
- [ ] Implement `split_text`, `generate_qr_images`, and `write_manifest`.
- [ ] Run pytest and confirm pass.

### Task 2: Desktop GUI
**Files:** Create `tools/txt_qr/app.py`.
- [ ] Add file and directory chooser controls, chunk-size validation, progress, and error dialogs.
- [ ] Import core functions and show generated output folder on success.

### Task 3: Packaging and user docs
**Files:** Create `tools/txt_qr/requirements.txt`, `tools/txt_qr/build_exe.ps1`, `tools/txt_qr/README.md`.
- [ ] Document install, run, and build commands.
- [ ] Add PyInstaller one-file command.
- [ ] Run core tests and a manual smoke generation.
