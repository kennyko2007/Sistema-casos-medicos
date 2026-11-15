(function () {
    'use strict';

    var cont = document.getElementById('mensajes');
    if (!cont) { return; }                       // sin conversación abierta, no hay nada que conectar

    var idCaso = cont.dataset.caso;
    var yo = cont.dataset.yo;                    // 'doctor' o 'paciente'
    var form = document.querySelector('.med-chat-form');
    var input = form ? form.querySelector('input[name="mensaje"]') : null;
    var estado = document.getElementById('chat-estado');

    var ws = null;
    var abierto = false;
    var intentos = 0;
    var MAX_INTENTOS = 8;

    function bajar() { cont.scrollTop = cont.scrollHeight; }

    function mostrarEstado(texto) { if (estado) { estado.textContent = texto; } }

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
            if (m.tipo !== 'mensaje') { return; }
            agregar(m);
            if (m.remitente !== yo) {            // lo estoy viendo: queda leído
                ws.send(JSON.stringify({ tipo: 'leido' }));
            }
        };

        ws.onclose = function () {
            abierto = false;
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

    bajar();
    conectar();
})();