// Zimdars Electric for PC: window, PDF saving and opening links in the browser.
const { app, BrowserWindow, ipcMain, shell, dialog, Menu } = require("electron");
const path = require("path");
const fs = require("fs");

function createWindow() {
  const win = new BrowserWindow({
    width: 1400, height: 900, minWidth: 1000, minHeight: 650, backgroundColor: "#14171A", title: "Zimdars Electric",
    icon: path.join(__dirname, "..", "icon.png"),
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.loadFile(path.join(__dirname, "index.html"));
  // Links open in the normal browser, never inside the app.
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e, url) => { if (!url.startsWith("file://")) { e.preventDefault(); if (/^https:\/\//.test(url)) shell.openExternal(url); } });
}

Menu.setApplicationMenu(Menu.buildFromTemplate([
  { label: "File", submenu: [{ role: "reload", label: "Reload" }, { type: "separator" }, { role: "quit", label: "Exit" }] },
  { label: "View", submenu: [{ role: "zoomIn" }, { role: "zoomOut" }, { role: "resetZoom" }, { type: "separator" }, { role: "togglefullscreen" }] },
]));

ipcMain.handle("open-external", (_e, url) => { if (typeof url === "string" && /^https:\/\//.test(url)) shell.openExternal(url); });

ipcMain.handle("save-pdf", async (_e, html, name) => {
  const w = new BrowserWindow({ show: false, webPreferences: { sandbox: true, javascript: false } });
  try {
    await w.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(String(html)));
    const pdf = await w.webContents.printToPDF({ pageSize: "Letter", printBackground: true, margins: { marginType: "default" } });
    const safe = String(name || "quote").replace(/[^A-Za-z0-9 ._-]+/g, "-");
    const { canceled, filePath } = await dialog.showSaveDialog({ defaultPath: path.join(app.getPath("documents"), safe + ".pdf"), filters: [{ name: "PDF", extensions: ["pdf"] }] });
    if (canceled || !filePath) return null;
    fs.writeFileSync(filePath, pdf);
    shell.openPath(filePath);
    return filePath;
  } finally { w.destroy(); }
});

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
