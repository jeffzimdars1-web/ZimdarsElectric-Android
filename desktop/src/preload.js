const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("zapi", {
  openExternal: (url) => ipcRenderer.invoke("open-external", url),
  savePdf: (html, name) => ipcRenderer.invoke("save-pdf", html, name),
});
