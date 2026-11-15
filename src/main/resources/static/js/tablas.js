(function () {
    'use strict';

    var D = document;

    function normalizar(texto) {
        return (texto || '')
            .normalize('NFD')
            .replace(/[\u0300-\u036f]/g, '')
            .toLowerCase();
    }

    function crearBoton(texto, etiqueta) {
        var boton = D.createElement('button');
        boton.type = 'button';
        boton.textContent = texto;
        boton.setAttribute('aria-label', etiqueta || ('Página ' + texto));
        return boton;
    }

    D.querySelectorAll('table.adm-table').forEach(function (tabla) {
        if (tabla.dataset.tablaLista === '1' || tabla.hasAttribute('data-sin-filtro')) {
            return;
        }

        var tbody = tabla.tBodies[0];
        if (!tbody) { return; }

        var filas = Array.prototype.slice.call(tbody.rows).filter(function (tr) {
            return tr.cells.length > 1 && !tr.classList.contains('vacio');
        });
        if (filas.length < 2) { return; }

        tabla.dataset.tablaLista = '1';

        var columnas = 1;
        if (tabla.tHead && tabla.tHead.rows.length) {
            columnas = tabla.tHead.rows[0].cells.length;
        } else if (filas[0]) {
            columnas = filas[0].cells.length;
        }

        var wrap = tabla.closest('.adm-table-wrap');
        var ancla = wrap || tabla;

        var tools = D.createElement('div');
        tools.className = 'tabla-tools';

        var buscar = D.createElement('div');
        buscar.className = 'tabla-search';
        buscar.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' +
            '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>';

        var input = D.createElement('input');
        input.type = 'search';
        input.className = 'adm-input';
        input.placeholder = 'Buscar en la tabla…';
        input.setAttribute('aria-label', 'Buscar en la tabla');
        buscar.appendChild(input);

        var meta = D.createElement('div');
        meta.className = 'tabla-meta';

        var info = D.createElement('span');
        info.className = 'tabla-info';

        var tamanoLabel = D.createElement('label');
        tamanoLabel.className = 'tabla-page-size';
        tamanoLabel.appendChild(D.createTextNode('Filas'));

        var tamano = D.createElement('select');
        tamano.className = 'adm-input';
        [10, 25, 50].forEach(function (n) {
            var opcion = D.createElement('option');
            opcion.value = String(n);
            opcion.textContent = String(n);
            tamano.appendChild(opcion);
        });
        tamanoLabel.appendChild(tamano);
        if (filas.length <= 10) { tamanoLabel.hidden = true; }

        meta.appendChild(info);
        meta.appendChild(tamanoLabel);
        tools.appendChild(buscar);
        tools.appendChild(meta);

        var pager = D.createElement('div');
        pager.className = 'tabla-pager';

        var sinResultados = D.createElement('tr');
        sinResultados.className = 'tabla-sin-resultados';
        sinResultados.hidden = true;

        var celda = D.createElement('td');
        celda.colSpan = columnas;
        celda.textContent = 'No se encontraron resultados.';
        sinResultados.appendChild(celda);
        tbody.appendChild(sinResultados);

        ancla.parentNode.insertBefore(tools, ancla);
        ancla.parentNode.insertBefore(pager, ancla.nextSibling);

        var estado = { pagina: 1, tamano: 10, q: '' };

        function pintarPager(totalPaginas) {
            pager.textContent = '';
            if (totalPaginas <= 1) {
                pager.hidden = true;
                return;
            }
            pager.hidden = false;

            var anterior = crearBoton('‹', 'Página anterior');
            anterior.disabled = estado.pagina === 1;
            anterior.addEventListener('click', function () {
                if (estado.pagina > 1) { estado.pagina--; aplicar(); }
            });
            pager.appendChild(anterior);

            var inicio = Math.max(1, estado.pagina - 2);
            var fin = Math.min(totalPaginas, inicio + 4);
            inicio = Math.max(1, fin - 4);

            for (var i = inicio; i <= fin; i++) {
                (function (numero) {
                    var boton = crearBoton(String(numero));
                    if (numero === estado.pagina) {
                        boton.setAttribute('aria-current', 'page');
                    }
                    boton.addEventListener('click', function () {
                        estado.pagina = numero;
                        aplicar();
                    });
                    pager.appendChild(boton);
                })(i);
            }

            var siguiente = crearBoton('›', 'Página siguiente');
            siguiente.disabled = estado.pagina === totalPaginas;
            siguiente.addEventListener('click', function () {
                if (estado.pagina < totalPaginas) { estado.pagina++; aplicar(); }
            });
            pager.appendChild(siguiente);
        }

        function aplicar() {
            var visibles = estado.q
                ? filas.filter(function (tr) {
                    return normalizar(tr.textContent).indexOf(estado.q) !== -1;
                })
                : filas;

            var total = visibles.length;
            var totalPaginas = Math.max(1, Math.ceil(total / estado.tamano));
            if (estado.pagina > totalPaginas) { estado.pagina = totalPaginas; }

            var inicio = (estado.pagina - 1) * estado.tamano;
            var fin = Math.min(inicio + estado.tamano, total);

            filas.forEach(function (tr) { tr.hidden = true; });
            visibles.slice(inicio, fin).forEach(function (tr) { tr.hidden = false; });

            sinResultados.hidden = total !== 0;
            info.textContent = total === 0
                ? 'Sin resultados'
                : 'Mostrando ' + (inicio + 1) + '–' + fin + ' de ' + total;

            pintarPager(totalPaginas);
        }

        var espera = null;
        input.addEventListener('input', function () {
            clearTimeout(espera);
            espera = setTimeout(function () {
                estado.q = normalizar(input.value.trim());
                estado.pagina = 1;
                aplicar();
            }, 120);
        });

        tamano.addEventListener('change', function () {
            estado.tamano = parseInt(tamano.value, 10) || 10;
            estado.pagina = 1;
            aplicar();
        });

        aplicar();
    });
})();
