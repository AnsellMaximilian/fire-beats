const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("firelabDesktop", {
  openAudio: () => ipcRenderer.invoke("audio:open"),
  readAudio: (id) => ipcRenderer.invoke("audio:read", id),
  analyzeAudio: (id, options) => ipcRenderer.invoke("audio:analyze", id, options),
  exportAnalysis: (suggestedName, payload) =>
    ipcRenderer.invoke("analysis:export", suggestedName, payload),
});
