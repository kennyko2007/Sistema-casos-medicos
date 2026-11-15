(function () {
    'use strict';

    var D = document;

    /* ------------------------------------------------------------------ */
    /* 1. Menú móvil                                                       */
    /* ------------------------------------------------------------------ */
    var header = D.querySelector('.site-header');
    var navToggle = D.querySelector('.nav-toggle');

    if (header && navToggle) {
        navToggle.addEventListener('click', function () {
            var abierto = header.classList.toggle('nav-open');
            navToggle.setAttribute('aria-expanded', String(abierto));
        });
        D.addEventListener('click', function (ev) {
            if (header.classList.contains('nav-open') && !header.contains(ev.target)) {
                header.classList.remove('nav-open');
                navToggle.setAttribute('aria-expanded', 'false');
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 2. Enlace activo del menú                                           */
    /* ------------------------------------------------------------------ */
    var enlaces = Array.prototype.slice.call(D.querySelectorAll('.nav-links a'));
    var activo = null;

    enlaces.forEach(function (a) {
        var ruta = a.pathname;
        var coincide = location.pathname === ruta || location.pathname.indexOf(ruta + '/') === 0;
        if (coincide && (!activo || ruta.length > activo.pathname.length)) {
            activo = a;
        }
    });
    if (activo) {
        activo.setAttribute('aria-current', 'page');
    }

    /* ------------------------------------------------------------------ */
    /* 3. Desplegables (usuario y campanita)                               */
    /* ------------------------------------------------------------------ */
    var menus = [];

    D.querySelectorAll('.user-menu').forEach(function (raiz) {
        var boton = raiz.querySelector('.user-btn');
        var panel = raiz.querySelector('[data-user-dropdown]');
        if (boton && panel) { menus.push({ boton: boton, panel: panel }); }
    });
    D.querySelectorAll('.notif[data-rol]').forEach(function (raiz) {
        var boton = raiz.querySelector('.notif-btn');
        var panel = raiz.querySelector('[data-notif-panel]');
        if (boton && panel) { menus.push({ boton: boton, panel: panel }); }
    });

    function cerrarMenus(excepto) {
        menus.forEach(function (m) {
            if (m === excepto) { return; }
            m.panel.hidden = true;
            m.boton.setAttribute('aria-expanded', 'false');
        });
    }

    menus.forEach(function (m) {
        m.boton.addEventListener('click', function (ev) {
            ev.stopPropagation();
            var abrir = m.panel.hidden;
            cerrarMenus(m);
            m.panel.hidden = !abrir;
            m.boton.setAttribute('aria-expanded', String(abrir));
        });
    });

    D.addEventListener('click', function (ev) {
        var dentro = menus.some(function (m) {
            return m.panel.contains(ev.target) || m.boton.contains(ev.target);
        });
        if (!dentro) { cerrarMenus(null); }
    });

    D.addEventListener('keydown', function (ev) {
        if (ev.key === 'Escape') { cerrarMenus(null); }
    });

    /* ------------------------------------------------------------------ */
    /* 4. Mostrar / ocultar contraseña                                     */
    /* ------------------------------------------------------------------ */
    D.querySelectorAll('[data-pass-toggle]').forEach(function (boton) {
        if (boton.dataset.passListo === '1') { return; }   // ya lo activó el script del login
        var wrap = boton.closest('.pass-wrap');
        var input = wrap ? wrap.querySelector('input') : null;
        if (!input) { return; }

        boton.dataset.passListo = '1';
        boton.addEventListener('click', function () {
            var mostrar = input.type === 'password';
            input.type = mostrar ? 'text' : 'password';
            boton.setAttribute('aria-pressed', String(mostrar));
            boton.setAttribute('aria-label', mostrar ? 'Ocultar contraseña' : 'Mostrar contraseña');
            input.focus({ preventScroll: true });
        });
    });

    /* ------------------------------------------------------------------ */
    /* 5. Campanita de mensajes                                            */
    /* ------------------------------------------------------------------ */
    var notif = D.querySelector('.notif[data-rol]');
    if (!notif) { return; }

    var rol = notif.getAttribute('data-rol');
    var badge = notif.querySelector('[data-notif-badge]');
    var totalEl = notif.querySelector('[data-notif-total]');
    var lista = notif.querySelector('[data-notif-list]');
    var vacio = notif.querySelector('[data-notif-empty]');
    var botonNotif = notif.querySelector('.notif-btn');
    var baseChat = rol === 'doctor' ? '/medico/chat/' : '/paciente/chat/';

    var previo = null;
    var ws = null;
    var reintentos = 0;
    var MAX_REINTENTOS = 6;
    var poll = null;

    function inicial(nombre) {
        var texto = (nombre || '?').trim();
        return texto ? texto.charAt(0).toUpperCase() : '?';
    }

    function mostrarAviso(c) {
        var chatAbierto = D.getElementById('mensajes');
        if (chatAbierto && String(chatAbierto.dataset.caso) === String(c.idCaso)) {
            return;                                  // ya se ve en la ventana de chat
        }
        if (window.SonidoAviso) { window.SonidoAviso.reproducir(); }

        var stack = D.querySelector('.toast-stack');
        if (!stack) {
            stack = D.createElement('div');
            stack.className = 'toast-stack';
            D.body.appendChild(stack);
        }

        var a = D.createElement('a');
        a.className = 'toast';
        a.href = baseChat + c.idCaso;

        var icono = D.createElement('span');
        icono.className = 'toast-icon';
        icono.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
            '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/>' +
            '<path d="M13.7 21a2 2 0 0 1-3.4 0"/></svg>';

        var cuerpo = D.createElement('span');
        cuerpo.className = 'toast-body';

        var titulo = D.createElement('strong');
        titulo.textContent = 'Nuevo mensaje de ' + (c.nombre || 'un usuario');

        var texto = D.createElement('span');
        texto.textContent = c.ultimo || '';

        cuerpo.appendChild(titulo);
        cuerpo.appendChild(texto);
        a.appendChild(icono);
        a.appendChild(cuerpo);
        stack.appendChild(a);

        setTimeout(function () { a.classList.add('out'); }, 6000);
        setTimeout(function () {
            if (a.parentNode) { a.parentNode.removeChild(a); }
        }, 6400);

        while (stack.children.length > 3) {
            stack.removeChild(stack.firstChild);
        }
    }

    function pintar(datos) {
        var conversaciones = Array.isArray(datos.conversaciones) ? datos.conversaciones : [];
        var total = Number(datos.total) || 0;

        badge.textContent = total > 99 ? '99+' : String(total);
        badge.hidden = total === 0;
        botonNotif.setAttribute('aria-label', total === 0
            ? 'Notificaciones'
            : 'Notificaciones: ' + total + ' sin leer');
        totalEl.textContent = total === 0
            ? 'Todo al día'
            : (total === 1 ? '1 sin leer' : total + ' sin leer');

        lista.textContent = '';
        vacio.hidden = conversaciones.length > 0;

        conversaciones.slice(0, 10).forEach(function (c) {
            var noLeidos = Number(c.noLeidos) || 0;

            var li = D.createElement('li');
            var enlace = D.createElement('a');
            enlace.className = 'notif-item' + (noLeidos > 0 ? ' unread' : '');
            enlace.href = baseChat + c.idCaso;

            var avatar = D.createElement('span');
            avatar.className = 'notif-avatar';
            avatar.textContent = inicial(c.nombre);

            var cuerpo = D.createElement('span');
            cuerpo.className = 'notif-body';

            var fila = D.createElement('span');
            fila.className = 'notif-name';

            var nombre = D.createElement('span');
            nombre.textContent = c.nombre || 'Usuario';

            var hora = D.createElement('time');
            hora.textContent = c.hora || '';

            fila.appendChild(nombre);
            fila.appendChild(hora);

            var texto = D.createElement('span');
            texto.className = 'notif-text';
            texto.textContent = c.ultimo || 'Sin mensajes';

            cuerpo.appendChild(fila);
            cuerpo.appendChild(texto);

            enlace.appendChild(avatar);
            enlace.appendChild(cuerpo);

            if (noLeidos > 0) {
                var contador = D.createElement('span');
                contador.className = 'notif-count';
                contador.textContent = noLeidos > 9 ? '9+' : String(noLeidos);
                enlace.appendChild(contador);
            }

            li.appendChild(enlace);
            lista.appendChild(li);
        });

        var siguiente = {};
        conversaciones.forEach(function (c) {
            var id = String(c.idCaso);
            var ultimo = Number(c.ultimoId) || 0;
            siguiente[id] = ultimo;

            if (previo && ultimo && Number(c.noLeidos) > 0) {
                var antes = previo[id];
                if (!antes || ultimo > antes) {
                    mostrarAviso(c);
                }
            }
        });
        previo = siguiente;
    }

    function cargarResumen() {
        fetch('/avisos/resumen', {
            headers: { 'Accept': 'application/json' },
            credentials: 'same-origin'
        }).then(function (r) {
            return r.ok ? r.json() : null;
        }).then(function (datos) {
            if (datos) { pintar(datos); }
        }).catch(function () { /* sin conexión: se reintenta */ });
    }

    function iniciarPoll() {
        if (!poll) { poll = setInterval(cargarResumen, 20000); }
    }

    function pararPoll() {
        if (poll) { clearInterval(poll); poll = null; }
    }

    function conectarAvisos() {
        var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
        try {
            ws = new WebSocket(proto + location.host + '/ws/avisos');
        } catch (e) {
            iniciarPoll();
            return;
        }

        ws.onopen = function () {
            reintentos = 0;
            pararPoll();
        };

        ws.onmessage = function (ev) {
            try {
                var m = JSON.parse(ev.data);
                if (m && m.tipo === 'resumen') { pintar(m); }
            } catch (e) { /* mensaje no válido */ }
        };

        ws.onclose = function () {
            ws = null;
            iniciarPoll();
            if (reintentos < MAX_REINTENTOS) {
                var espera = Math.min(1500 * Math.pow(2, reintentos), 20000);
                reintentos++;
                setTimeout(conectarAvisos, espera);
            }
        };

        ws.onerror = function () {
            if (ws) { try { ws.close(); } catch (e) { } }
        };
    }

    D.addEventListener('visibilitychange', function () {
        if (D.visibilityState === 'visible') {
            cargarResumen();
            if (!ws) { reintentos = 0; conectarAvisos(); }
        }
    });

    cargarResumen();
    conectarAvisos();
})();
