(function () {
    'use strict';

    var CLAVE = 'sonidoMensajes';
    var ctx = null;

    function activo() {
        try { return localStorage.getItem(CLAVE) !== '0'; } catch (e) { return true; }
    }

    function desbloquear() {
        if (!ctx) {
            var AC = window.AudioContext || window.webkitAudioContext;
            if (!AC) { return; }
            try { ctx = new AC(); } catch (e) { return; }
        }
        if (ctx.state === 'suspended') { ctx.resume(); }
    }

    function reproducir() {
        if (!activo()) { return; }
        desbloquear();
        if (!ctx) { return; }

        var t = ctx.currentTime;
        [[880, 0], [1244.5, 0.13]].forEach(function (par) {
            var osc = ctx.createOscillator();
            var gan = ctx.createGain();
            var inicio = t + par[1];

            osc.type = 'sine';
            osc.frequency.setValueAtTime(par[0], inicio);
            gan.gain.setValueAtTime(0.0001, inicio);
            gan.gain.exponentialRampToValueAtTime(0.16, inicio + 0.02);
            gan.gain.exponentialRampToValueAtTime(0.0001, inicio + 0.24);

            osc.connect(gan);
            gan.connect(ctx.destination);
            osc.start(inicio);
            osc.stop(inicio + 0.26);
        });
    }

    function pintarBotones(estado) {
        document.querySelectorAll('[data-sonido-toggle]').forEach(function (boton) {
            boton.textContent = estado ? 'Sonido: sí' : 'Sonido: no';
            boton.setAttribute('aria-pressed', estado ? 'true' : 'false');
            boton.classList.toggle('sonido-apagado', !estado);
        });
    }

    function alternar() {
        try { localStorage.setItem(CLAVE, activo() ? '0' : '1'); } catch (e) { /* modo privado */ }
        var ahora = activo();
        pintarBotones(ahora);
        if (ahora) { reproducir(); }
        return ahora;
    }

    function iniciar() {
        pintarBotones(activo());
        document.querySelectorAll('[data-sonido-toggle]').forEach(function (boton) {
            boton.addEventListener('click', function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                alternar();
            });
        });
        ['pointerdown', 'keydown'].forEach(function (evento) {
            document.addEventListener(evento, desbloquear, { once: true });
        });
    }

    window.SonidoAviso = {
        activo: activo,
        alternar: alternar,
        reproducir: reproducir,
        desbloquear: desbloquear
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', iniciar);
    } else {
        iniciar();
    }
})();
