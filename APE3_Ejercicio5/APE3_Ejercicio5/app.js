const API_URL = "http://localhost:8080/api";
let timerPolling = null;

document.addEventListener("DOMContentLoaded", () => {
    document.getElementById("btnIniciar").addEventListener("click", iniciarSimulacion);
    document.getElementById("btnLimpiar").addEventListener("click", limpiarSimulacion);
});

async function iniciarSimulacion() {
    const numDocs = document.getElementById("numDocs").value;

    try {
        const response = await fetch(`${API_URL}/iniciar`, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: `numDocumentos=${numDocs}`
        });

        if (response.ok) {
            document.getElementById("btnIniciar").disabled = true;
            if (timerPolling) clearInterval(timerPolling);
            timerPolling = setInterval(consultarEstado, 400);
        }
    } catch (error) {
        alert("Error al conectar con el servidor Java Backend. Asegúrese de que CentroImpresionServer esté ejecutándose.");
    }
}

async function consultarEstado() {
    try {
        const response = await fetch(`${API_URL}/estado`);
        const data = await response.json();

        actualizarUI(data);

        if (data.estado === "COMPLETADO") {
            clearInterval(timerPolling);
            document.getElementById("btnIniciar").disabled = false;
        }
    } catch (error) {
        console.error("Error obteniendo el estado:", error);
    }
}

function actualizarUI(data) {
    // Actualizar métricas
    document.getElementById("metricDiseno").textContent = data.enDiseno > 0 ? `#${data.enDiseno}` : "0";
    document.getElementById("metricRevision").textContent = data.enRevision > 0 ? `#${data.enRevision}` : "0";
    document.getElementById("metricImpresos").textContent = `${data.impresos} / ${data.total}`;

    const badgeEstado = document.getElementById("metricEstado");
    badgeEstado.textContent = data.estado;
    badgeEstado.className = `badge badge-${data.estado.toLowerCase()}`;

    // Actualizar visualización del pipeline
    renderSlot("slotDiseno", data.enDiseno, "🎨 Diseñando Doc #");
    renderSlot("slotRevision", data.enRevision, "🔍 Revisando Doc #");
    renderSlot("slotImpresion", data.enDiseno === 0 && data.enRevision === 0 && data.impresos > 0 && data.estado === "EN_PROCESO" ? data.impresos : 0, "🖨️ Imprimiendo Doc #");

    // Actualizar semáforos visuales
    document.getElementById("semDiseno").textContent = data.enDiseno > 0 ? "0 (En uso)" : (data.estado === "EN_PROCESO" && data.enRevision === 0 ? "1" : "0");
    document.getElementById("semRevision").textContent = data.enRevision > 0 ? "0 (En uso)" : "0";
    document.getElementById("semImpresion").textContent = "0";

    // Actualizar Bitácora
    const bitacora = document.getElementById("bitacoraLog");
    bitacora.innerHTML = data.log.map(linea => `<div class="log-entry">${linea}</div>`).join("");
    bitacora.scrollTop = bitacora.scrollHeight;
}

function renderSlot(slotId, docNum, texto) {
    const slot = document.getElementById(slotId);
    if (docNum > 0) {
        slot.innerHTML = `<div class="doc-card">${texto}${docNum}</div>`;
    } else {
        slot.innerHTML = `<span class="placeholder">Inactivo</span>`;
    }
}

async function limpiarSimulacion() {
    if (timerPolling) clearInterval(timerPolling);
    try {
        await fetch(`${API_URL}/limpiar`, { method: "POST" });
        document.getElementById("btnIniciar").disabled = false;
        document.getElementById("metricDiseno").textContent = "0";
        document.getElementById("metricRevision").textContent = "0";
        document.getElementById("metricImpresos").textContent = "0";
        document.getElementById("metricEstado").textContent = "INACTIVO";
        document.getElementById("metricEstado").className = "badge badge-inactivo";
        
        document.getElementById("slotDiseno").innerHTML = '<span class="placeholder">Esperando documento...</span>';
        document.getElementById("slotRevision").innerHTML = '<span class="placeholder">Esperando documento...</span>';
        document.getElementById("slotImpresion").innerHTML = '<span class="placeholder">Esperando documento...</span>';
        
        document.getElementById("bitacoraLog").innerHTML = '<div class="log-entry system">[SISTEMA]: Estado reiniciado.</div>';
    } catch (error) {
        console.error("Error al reiniciar:", error);
    }
}