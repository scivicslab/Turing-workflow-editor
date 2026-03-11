(function () {
    'use strict';

    var tableBody = document.getElementById('tableBody');
    var workflowName = document.getElementById('workflowName');
    var runBtn = document.getElementById('runBtn');
    var stopBtn = document.getElementById('stopBtn');
    var addRowBtn = document.getElementById('addRowBtn');
    var addSubRowBtn = document.getElementById('addSubRowBtn');
    var exportYamlBtn = document.getElementById('exportYamlBtn');
    var importYamlBtn = document.getElementById('importYamlBtn');
    var clearLogBtn = document.getElementById('clearLogBtn');
    var logOutput = document.getElementById('logOutput');
    var maxIterationsInput = document.getElementById('maxIterations');

    var actorsBtn = document.getElementById('actorsBtn');
    var actorPanel = document.getElementById('actorPanel');
    var actorPanelClose = document.getElementById('actorPanelClose');
    var actorPanelBody = document.getElementById('actorPanelBody');
    var themeSelect = document.getElementById('themeSelect');

    var sessionId = 'session-' + Date.now();
    var eventSource = null;
    var isRunning = false;
    var dragRow = null;

    // --- Theme ---
    var THEME_KEY = 'workflow-editor-theme';
    var savedTheme = localStorage.getItem(THEME_KEY) || 'dark-catppuccin';
    document.documentElement.setAttribute('data-theme', savedTheme);
    themeSelect.value = savedTheme;

    themeSelect.addEventListener('change', function () {
        var theme = themeSelect.value;
        document.documentElement.setAttribute('data-theme', theme);
        localStorage.setItem(THEME_KEY, theme);
    });

    // --- SSE Connection ---

    function connectSSE() {
        if (eventSource) {
            eventSource.close();
        }
        eventSource = new EventSource('/api/events?session=' + encodeURIComponent(sessionId));

        eventSource.onmessage = function (e) {
            try {
                var event = JSON.parse(e.data);
                appendLog(event.type, event.message);

                if (event.type === 'completed' || event.type === 'error' || event.type === 'stopped') {
                    setRunning(false);
                }
            } catch (err) {
                // ignore parse errors (heartbeat etc.)
            }
        };

        eventSource.onerror = function () {
            // auto-reconnect is built into EventSource
        };
    }

    // --- Log ---

    function appendLog(type, message) {
        var div = document.createElement('div');
        div.className = 'log-entry ' + (type || 'info');
        if (type === 'output') {
            div.textContent = message || '';
        } else {
            var ts = new Date().toLocaleTimeString();
            div.textContent = '[' + ts + '] ' + (message || '');
        }
        logOutput.appendChild(div);
        logOutput.scrollTop = logOutput.scrollHeight;
    }

    clearLogBtn.addEventListener('click', function () {
        logOutput.innerHTML = '';
    });

    // --- Table Editing ---

    // isSubAction: true means this row is a sub-action (no from/to, indented)
    function createRow(from, to, actor, method, args, isSubAction) {
        var tr = document.createElement('tr');
        tr.draggable = true;
        tr.dataset.subaction = isSubAction ? 'true' : 'false';

        if (isSubAction) {
            tr.className = 'sub-action-row';
            tr.innerHTML =
                '<td class="row-num"></td>' +
                '<td class="sub-indicator" colspan="2"><span class="sub-arrow">&#x21B3;</span></td>' +
                '<td><input class="col-actor" type="text" value="' + escapeAttr(actor || '') + '" placeholder="actor"></td>' +
                '<td><input class="col-method" type="text" value="' + escapeAttr(method || '') + '" placeholder="method"></td>' +
                '<td><textarea class="col-args" placeholder="arguments" rows="1">' + escapeAttr(args || '') + '</textarea></td>' +
                '<td><div class="row-actions">' +
                '<button class="drag-handle" title="Drag to reorder">&#x2630;</button>' +
                '<button class="add-sub-btn" title="Add sub-action below">&#x21B3;</button>' +
                '<button class="delete-btn" title="Delete row">&times;</button>' +
                '</div></td>';
        } else {
            tr.innerHTML =
                '<td class="row-num"></td>' +
                '<td><input class="col-from" type="text" value="' + escapeAttr(from || '') + '" placeholder="0"></td>' +
                '<td><input class="col-to" type="text" value="' + escapeAttr(to || '') + '" placeholder="1"></td>' +
                '<td><input class="col-actor" type="text" value="' + escapeAttr(actor || '') + '" placeholder="actor"></td>' +
                '<td><input class="col-method" type="text" value="' + escapeAttr(method || '') + '" placeholder="method"></td>' +
                '<td><textarea class="col-args" placeholder="arguments" rows="1">' + escapeAttr(args || '') + '</textarea></td>' +
                '<td><div class="row-actions">' +
                '<button class="drag-handle" title="Drag to reorder">&#x2630;</button>' +
                '<button class="insert-btn" title="Insert transition below">+</button>' +
                '<button class="add-sub-btn" title="Add sub-action">&#x21B3;</button>' +
                '<button class="delete-btn" title="Delete row">&times;</button>' +
                '</div></td>';
        }

        // Auto-resize textarea
        var argsArea = tr.querySelector('.col-args');
        argsArea.addEventListener('input', function () { autoResize(this); });
        autoResize(argsArea);

        // Delete button
        tr.querySelector('.delete-btn').addEventListener('click', function () {
            // If deleting a transition row, also delete its sub-action rows
            if (!isSubAction) {
                var next = tr.nextElementSibling;
                while (next && next.dataset.subaction === 'true') {
                    var toRemove = next;
                    next = next.nextElementSibling;
                    toRemove.remove();
                }
            }
            tr.remove();
            renumberRows();
        });

        // Insert transition button (only on transition rows)
        var insertBtn = tr.querySelector('.insert-btn');
        if (insertBtn) {
            insertBtn.addEventListener('click', function () {
                var nextFrom = tr.querySelector('.col-to').value;
                // Find the last sub-action of this transition
                var insertAfter = tr;
                var next = tr.nextElementSibling;
                while (next && next.dataset.subaction === 'true') {
                    insertAfter = next;
                    next = next.nextElementSibling;
                }
                var newRow = createRow(nextFrom, '', '', '', '', false);
                insertAfter.parentNode.insertBefore(newRow, insertAfter.nextSibling);
                renumberRows();
                newRow.querySelector('.col-to').focus();
            });
        }

        // Add sub-action button
        tr.querySelector('.add-sub-btn').addEventListener('click', function () {
            // Find the last sub-action of this transition group
            var insertAfter = tr;
            if (!isSubAction) {
                var next = tr.nextElementSibling;
                while (next && next.dataset.subaction === 'true') {
                    insertAfter = next;
                    next = next.nextElementSibling;
                }
            }
            var newRow = createRow('', '', '', '', '', true);
            insertAfter.parentNode.insertBefore(newRow, insertAfter.nextSibling);
            renumberRows();
            newRow.querySelector('.col-actor').focus();
        });

        // Drag and drop
        tr.addEventListener('dragstart', function (e) {
            dragRow = tr;
            tr.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        tr.addEventListener('dragend', function () {
            tr.classList.remove('dragging');
            dragRow = null;
            document.querySelectorAll('.drag-over').forEach(function (el) {
                el.classList.remove('drag-over');
            });
            renumberRows();
        });

        tr.addEventListener('dragover', function (e) {
            e.preventDefault();
            if (dragRow && dragRow !== tr) {
                tr.classList.add('drag-over');
            }
        });

        tr.addEventListener('dragleave', function () {
            tr.classList.remove('drag-over');
        });

        tr.addEventListener('drop', function (e) {
            e.preventDefault();
            tr.classList.remove('drag-over');
            if (dragRow && dragRow !== tr) {
                tableBody.insertBefore(dragRow, tr);
            }
        });

        // Tab on last field (textarea) of last row adds a new transition
        argsArea.addEventListener('keydown', function (e) {
            if (e.key === 'Tab' && !e.shiftKey) {
                var rows = tableBody.querySelectorAll('tr');
                if (tr === rows[rows.length - 1]) {
                    e.preventDefault();
                    var lastTo = getLastTo();
                    var nr = createRow(lastTo, '', '', '', '', false);
                    tableBody.appendChild(nr);
                    renumberRows();
                    nr.querySelector('.col-to').focus();
                }
            }
        });

        return tr;
    }

    function getLastTo() {
        var allRows = tableBody.querySelectorAll('tr:not(.sub-action-row)');
        if (allRows.length > 0) {
            var toInput = allRows[allRows.length - 1].querySelector('.col-to');
            return toInput ? toInput.value : '';
        }
        return '';
    }

    function addRow(from, to, actor, method, args, isSubAction) {
        var tr = createRow(from, to, actor, method, args, isSubAction || false);
        tableBody.appendChild(tr);
        renumberRows();
        return tr;
    }

    function insertRowAfter(refRow, from, to, actor, method, args, isSubAction) {
        var tr = createRow(from, to, actor, method, args, isSubAction || false);
        refRow.parentNode.insertBefore(tr, refRow.nextSibling);
        renumberRows();
        return tr;
    }

    function renumberRows() {
        var rows = tableBody.querySelectorAll('tr');
        var transitionNum = 0;
        for (var i = 0; i < rows.length; i++) {
            var numCell = rows[i].querySelector('.row-num');
            if (rows[i].dataset.subaction === 'true') {
                numCell.textContent = '';
            } else {
                transitionNum++;
                numCell.textContent = transitionNum;
            }
        }
    }

    addRowBtn.addEventListener('click', function () {
        var lastTo = getLastTo();
        var nr = addRow(lastTo, '', '', '', '', false);
        nr.querySelector(lastTo ? '.col-to' : '.col-from').focus();
    });

    addSubRowBtn.addEventListener('click', function () {
        // Add sub-action after the last row
        var rows = tableBody.querySelectorAll('tr');
        if (rows.length === 0) {
            appendLog('error', 'Add a transition row first');
            return;
        }
        var nr = addRow('', '', '', '', '', true);
        nr.querySelector('.col-actor').focus();
    });

    // --- Get rows from table ---

    function getRows() {
        var rows = tableBody.querySelectorAll('tr');
        var result = [];
        var currentFrom = '';
        var currentTo = '';

        for (var i = 0; i < rows.length; i++) {
            var tr = rows[i];
            var isSub = tr.dataset.subaction === 'true';
            var actor = tr.querySelector('.col-actor').value.trim();
            var method = tr.querySelector('.col-method').value.trim();
            var args = tr.querySelector('.col-args').value.trim();

            if (!isSub) {
                currentFrom = tr.querySelector('.col-from').value.trim();
                currentTo = tr.querySelector('.col-to').value.trim();
            }

            if (actor && method && currentFrom && currentTo) {
                result.push({
                    from: isSub ? '' : currentFrom,
                    to: isSub ? '' : currentTo,
                    actor: actor,
                    method: method,
                    arguments: args
                });
            }
        }
        return result;
    }

    // --- Run / Stop ---

    function setRunning(running) {
        isRunning = running;
        runBtn.disabled = running;
        stopBtn.disabled = !running;
    }

    runBtn.addEventListener('click', function () {
        var rows = getRows();
        if (rows.length === 0) {
            appendLog('error', 'No valid rows to run');
            return;
        }

        setRunning(true);
        appendLog('info', 'Starting workflow: ' + workflowName.value);

        fetch('/api/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: workflowName.value,
                rows: rows,
                maxIterations: parseInt(maxIterationsInput.value, 10) || 100
            })
        }).then(function (res) {
            return res.json();
        }).then(function (data) {
            if (data.status === 'error') {
                appendLog('error', data.message);
                setRunning(false);
            }
        }).catch(function (err) {
            appendLog('error', 'Failed to start: ' + err.message);
            setRunning(false);
        });
    });

    stopBtn.addEventListener('click', function () {
        fetch('/api/stop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({})
        });
    });

    // --- Export YAML ---

    exportYamlBtn.addEventListener('click', function () {
        var rows = getRows();
        var yaml = 'name: ' + workflowName.value + '\n';
        yaml += 'steps:\n';

        var inTransition = false;

        for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var isNew = row.from && row.to;

            if (isNew) {
                yaml += '  - states: ["' + row.from + '", "' + row.to + '"]\n';
                yaml += '    actions:\n';
                inTransition = true;
            }

            if (inTransition) {
                yaml += '      - actor: ' + row.actor + '\n';
                yaml += '        method: ' + row.method + '\n';
                if (row.arguments) {
                    var args = row.arguments.trim();
                    if (args.startsWith('[') || args.startsWith('{')) {
                        yaml += '        arguments: ' + args + '\n';
                    } else {
                        yaml += '        arguments: "' + args.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"\n';
                    }
                }
            }
        }

        navigator.clipboard.writeText(yaml).then(function () {
            appendLog('info', 'YAML copied to clipboard');
        }).catch(function () {
            prompt('YAML output:', yaml);
        });
    });

    // --- Import YAML ---

    importYamlBtn.addEventListener('click', function () {
        var yaml = prompt('Paste YAML here:');
        if (!yaml) return;
        parseAndLoadYaml(yaml);
    });

    function parseAndLoadYaml(yaml) {
        try {
            tableBody.innerHTML = '';
            var lines = yaml.split('\n');
            var currentFrom = '';
            var currentTo = '';
            var firstActionInTransition = true;

            for (var i = 0; i < lines.length; i++) {
                var trimmed = lines[i].trim();

                if (trimmed.startsWith('name:')) {
                    workflowName.value = trimmed.substring(5).trim().replace(/^["']|["']$/g, '');
                }

                if (trimmed.startsWith('- states:')) {
                    var match = trimmed.match(/\["?([^",\]]+)"?\s*,\s*"?([^",\]]+)"?\]/);
                    if (match) {
                        currentFrom = match[1];
                        currentTo = match[2];
                        firstActionInTransition = true;
                    } else {
                        // Multi-line format: - states:\n  - "0"\n  - "100"
                        var stateValues = [];
                        var j = i + 1;
                        while (j < lines.length) {
                            var stLine = lines[j].trim();
                            if (stLine.startsWith('- ') && !stLine.startsWith('- states:') && !stLine.startsWith('- actor:')) {
                                var val = stLine.substring(2).trim().replace(/^["']|["']$/g, '');
                                if (val.indexOf(':') !== -1) break;
                                stateValues.push(val);
                                j++;
                            } else {
                                break;
                            }
                        }
                        if (stateValues.length >= 2) {
                            currentFrom = stateValues[0];
                            currentTo = stateValues[1];
                            firstActionInTransition = true;
                            i = j - 1;
                        }
                    }
                }

                if (trimmed.startsWith('- actor:')) {
                    var actor = trimmed.substring(8).trim().replace(/^["']|["']$/g, '');
                    var method = '';
                    var args = '';

                    if (i + 1 < lines.length && lines[i + 1].trim().startsWith('method:')) {
                        i++;
                        method = lines[i].trim().substring(7).trim().replace(/^["']|["']$/g, '');
                    }

                    if (i + 1 < lines.length && lines[i + 1].trim().startsWith('arguments:')) {
                        i++;
                        args = lines[i].trim().substring(10).trim().replace(/^["']|["']$/g, '');
                    }

                    if (firstActionInTransition) {
                        addRow(currentFrom, currentTo, actor, method, args, false);
                        firstActionInTransition = false;
                    } else {
                        addRow('', '', actor, method, args, true);
                    }
                }
            }

            appendLog('info', 'Imported ' + tableBody.querySelectorAll('tr').length + ' rows');

        } catch (err) {
            appendLog('error', 'Failed to parse YAML: ' + err.message);
        }
    }

    // --- Utility ---

    function escapeAttr(s) {
        return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function autoResize(el) {
        el.style.height = 'auto';
        el.style.height = Math.max(28, el.scrollHeight) + 'px';
    }

    // --- Actor Panel ---

    actorsBtn.addEventListener('click', function () {
        actorPanel.style.display = actorPanel.style.display === 'none' ? 'flex' : 'none';
        if (actorPanel.style.display === 'flex') {
            loadActorTree();
        }
    });

    actorPanelClose.addEventListener('click', function () {
        actorPanel.style.display = 'none';
    });

    function loadActorTree() {
        actorPanelBody.innerHTML = '<div style="color:var(--text-secondary)">Loading...</div>';
        fetch('/api/actors').then(function (res) {
            return res.json();
        }).then(function (actors) {
            actorPanelBody.innerHTML = '';
            if (actors.length === 0) {
                actorPanelBody.innerHTML = '<div style="color:var(--text-secondary)">No actors registered</div>';
                return;
            }
            actors.forEach(function (actor) {
                var card = document.createElement('div');
                card.className = 'actor-card';

                var header = '<div class="actor-card-header">' +
                    '<span class="actor-name">' + escapeAttr(actor.name) + '</span>' +
                    '<span class="actor-type">' + escapeAttr(actor.type) + '</span>' +
                    '</div>';

                var body = '<div class="actor-card-body">';

                // Actions
                if (actor.actions && actor.actions.length > 0) {
                    body += '<div class="actor-section-label">Actions</div>';
                    body += '<div class="actor-actions-list">';
                    actor.actions.forEach(function (action) {
                        body += '<span class="actor-action-tag">' + escapeAttr(action) + '</span>';
                    });
                    body += '</div>';
                }

                // Children
                if (actor.children && actor.children.length > 0) {
                    body += '<div class="actor-section-label">Children</div>';
                    body += '<div class="actor-children-list">';
                    actor.children.forEach(function (child) {
                        body += '<span class="actor-child-name">' + escapeAttr(child) + '</span> ';
                    });
                    body += '</div>';
                }

                // Parent
                if (actor.parent) {
                    body += '<div class="actor-section-label">Parent</div>';
                    body += '<div style="font-size:12px;color:var(--text-secondary)">' + escapeAttr(actor.parent) + '</div>';
                }

                body += '</div>';

                card.innerHTML = header + body;
                actorPanelBody.appendChild(card);
            });
        }).catch(function (err) {
            actorPanelBody.innerHTML = '<div style="color:var(--accent-red)">Error: ' + err.message + '</div>';
        });
    }

    // --- Sync with server state ---

    function loadFromServer() {
        fetch('/api/workflow').then(function (res) {
            return res.json();
        }).then(function (dto) {
            if (dto.rows && dto.rows.length > 0) {
                tableBody.innerHTML = '';
                workflowName.value = dto.name || 'workflow';
                if (dto.maxIterations) {
                    maxIterationsInput.value = dto.maxIterations;
                }
                var currentFrom = '';
                var currentTo = '';
                for (var i = 0; i < dto.rows.length; i++) {
                    var r = dto.rows[i];
                    var isSub = (!r.from || r.from === '') && (!r.to || r.to === '');
                    if (!isSub) {
                        currentFrom = r.from;
                        currentTo = r.to;
                    }
                    addRow(isSub ? '' : r.from, isSub ? '' : r.to,
                           r.actor, r.method, r.arguments || '', isSub);
                }
                appendLog('info', 'Loaded workflow from server: ' + dto.name + ' (' + dto.rows.length + ' rows)');
            } else {
                // No server state — show default
                addRow('0', '1', 'log', 'info', 'Workflow started', false);
                addRow('1', 'end', 'log', 'info', 'Workflow finished', false);
                addRow('!end', 'end', 'log', 'warn', 'Unexpected state - terminating', false);
            }
        }).catch(function () {
            // Server unavailable — show default
            addRow('0', '1', 'log', 'info', 'Workflow started', false);
            addRow('1', 'end', 'log', 'info', 'Workflow finished', false);
            addRow('!end', 'end', 'log', 'warn', 'Unexpected state - terminating', false);
        });
    }

    // --- Initialize ---

    loadFromServer();
    connectSSE();

})();
