(function () {
    'use strict';

    var cont = document.getElementById('mensajes');
    if (!cont) { return; }                       // sin conversación abierta, no hay nada que conectar

    var idCaso = cont.dataset.caso;
    var yo = cont.dataset.yo;                    // 'doctor' o 'paciente'
    var form = document.querySelector('.med-chat-form');
    var input = form ? form.querySelector('input[name="mensaje"]') : null;
    var estado = document.getElementById('chat-estado');
    var escribiendo = document.getElementById('chat-escribiendo');

    var ws = null;
    var abierto = false;
    var intentos = 0;
    var MAX_INTENTOS = 8;

    var ultimoAviso = 0;
    var ESPERA_AVISO = 1500;
    var ocultarEscribiendoTimer = null;

    function bajar() { cont.scrollTop = cont.scrollHeight; }

    function mostrarEstado(texto) { if (estado) { estado.textContent = texto; } }

    function mostrarEscribiendo() {
        if (!escribiendo) { return; }
        escribiendo.hidden = false;
        clearTimeout(ocultarEscribiendoTimer);
        ocultarEscribiendoTimer = setTimeout(ocultarEscribiendo, 3000);
    }

    function ocultarEscribiendo() {
        clearTimeout(ocultarEscribiendoTimer);
        if (escribiendo) { escribiendo.hidden = true; }
    }

    function avisarEscribiendo() {
        if (!abierto || !ws || ws.readyState !== WebSocket.OPEN) { return; }
        var ahora = Date.now();
        if (ahora - ultimoAviso < ESPERA_AVISO) { return; }
        ultimoAviso = ahora;
        ws.send(JSON.stringify({ tipo: 'escribiendo' }));
    }

    function agregar(m) {
        var vacio = cont.querySelector('.adm-hint');
        if (vacio) { vacio.remove(); }

        var div = document.createElement('div');
        div.className = 'burbuja ' + (m.remitente === yo ? 'burbuja-mia' : 'burbuja-otro');

        var span = document.createElement('span');
        span.textContent = m.mensaje;            // textContent: el texto nunca se interpreta como HTML

        var time = document.createElement('time');
        time.textContent = m.fecha;

        div.appendChild(span);
        div.appendChild(time);
        cont.appendChild(div);
        bajar();
    }

    function agregarArchivo(m) {
        var vacio = cont.querySelector('.adm-hint');
        if (vacio) { vacio.remove(); }

        var div = document.createElement('div');
        div.className = 'burbuja ' + (m.remitente === yo ? 'burbuja-mia' : 'burbuja-otro');

        var enlace = document.createElement('a');
        enlace.className = 'burbuja-archivo' +
            ((m.tipoArchivo || '').indexOf('image/') === 0 ? ' es-imagen' : '');
        enlace.href = m.url;                     // el archivo se descarga por HTTP, no por WebSocket
        enlace.target = '_blank';
        enlace.rel = 'noopener';
        enlace.textContent = m.adjunto || 'Archivo adjunto';

        var time = document.createElement('time');
        time.textContent = m.fecha;

        div.appendChild(enlace);
        div.appendChild(time);
        cont.appendChild(div);
        bajar();
    }

    function conectar() {
        var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
        ws = new WebSocket(proto + location.host + '/ws/chat?caso=' + encodeURIComponent(idCaso));

        ws.onopen = function () {
            abierto = true;
            intentos = 0;
            mostrarEstado('En vivo');
            ws.send(JSON.stringify({ tipo: 'leido' }));
        };

        ws.onmessage = function (e) {
            var m;
            try { m = JSON.parse(e.data); } catch (err) { return; }

            if (m.tipo === 'escribiendo') {
                if (m.remitente !== yo) { mostrarEscribiendo(); }
                return;
            }
            if (m.tipo === 'adjunto') {
                ocultarEscribiendo();
                agregarArchivo(m);
                if (m.remitente !== yo) {
                    if (window.SonidoAviso) { window.SonidoAviso.reproducir(); }
                    ws.send(JSON.stringify({ tipo: 'leido' }));
                }
                return;
            }
            if (m.tipo !== 'mensaje') { return; }

            ocultarEscribiendo();
            agregar(m);

            if (m.remitente !== yo) {            // lo estoy viendo: queda leído
                if (window.SonidoAviso) { window.SonidoAviso.reproducir(); }
                ws.send(JSON.stringify({ tipo: 'leido' }));
            }
        };

        ws.onclose = function () {
            abierto = false;
            ocultarEscribiendo();
            if (intentos >= MAX_INTENTOS) {
                mostrarEstado('Sin conexión en vivo. Recarga la página.');
                return;
            }
            var espera = Math.min(1000 * Math.pow(2, intentos), 10000);
            intentos++;
            mostrarEstado('Reconectando…');
            setTimeout(conectar, espera);
        };
    }

    if (input) {
        input.addEventListener('input', function () {
            if (input.value.trim()) { avisarEscribiendo(); }
        });
    }

    if (form && input) {
        form.addEventListener('submit', function (ev) {
            // Si el WebSocket está abierto se envía en vivo; si no, el formulario normal sigue como respaldo
            if (abierto && ws && ws.readyState === WebSocket.OPEN) {
                ev.preventDefault();
                var texto = input.value.trim();
                if (!texto) { return; }
                ws.send(JSON.stringify({ tipo: 'mensaje', mensaje: texto }));
                input.value = '';
                input.focus();
            }
        });
    }

    var adjForm = document.getElementById('chat-adjunto-form');
    var adjInput = document.getElementById('chat-archivo');
    if (adjForm && adjInput) {
        adjInput.addEventListener('change', function () {
            var f = adjInput.files && adjInput.files[0];
            if (!f) { return; }

            var nombre = (f.name || '').toLowerCase();
            var tipoOk = ['application/pdf', 'image/jpeg', 'image/png'].indexOf(f.type) >= 0
                || /\.(pdf|jpe?g|png)$/.test(nombre);
            if (!tipoOk) {
                alert('Solo se pueden enviar archivos PDF, JPG o PNG.');
                adjInput.value = '';
                return;
            }
            if (f.size > 10 * 1024 * 1024) {
                alert('El archivo supera los 10 MB.');
                adjInput.value = '';
                return;
            }

            var boton = adjForm.querySelector('.chat-file');
            if (boton) { boton.classList.add('enviando'); }
            adjForm.submit();                    // sube por HTTP y la página se recarga con el adjunto
        });
    }

    bajar();
    conectar();
})();
