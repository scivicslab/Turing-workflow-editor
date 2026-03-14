(function () {
    'use strict';

    var stepsContainer = document.getElementById('stepsContainer');
    var descriptionArea = document.getElementById('workflowDescription');
    var runBtn = document.getElementById('runBtn');
    var stopBtn = document.getElementById('stopBtn');
    var addStepBtn = document.getElementById('addStepBtn');
    var exportYamlBtn = document.getElementById('exportYamlBtn');
    var importYamlBtn = document.getElementById('importYamlBtn');
    var clearLogBtn = document.getElementById('clearLogBtn');
    var logOutput = document.getElementById('logOutput');
    var maxIterationsInput = document.getElementById('maxIterations');
    var infiniteCheck = document.getElementById('infiniteCheck');

    infiniteCheck.addEventListener('change', function () {
        maxIterationsInput.disabled = infiniteCheck.checked;
    });

    function getMaxIterations() {
        return infiniteCheck.checked ? 2147483647 : (parseInt(maxIterationsInput.value, 10) || 100);
    }

    var actorsBtn = document.getElementById('actorsBtn');
    var actorPanel = document.getElementById('actorPanel');
    var actorPanelClose = document.getElementById('actorPanelClose');
    var actorPanelBody = document.getElementById('actorPanelBody');
    var themeSelect = document.getElementById('themeSelect');

    var tabList = document.getElementById('tabList');
    var addTabBtn = document.getElementById('addTabBtn');
    var resumeBtn = document.getElementById('resumeBtn');
    var logResizer = document.getElementById('logResizer');
    var logSection = document.querySelector('.log-section');
    var logLevelSelect = document.getElementById('logLevelSelect');
    var globalDelayInput = document.getElementById('globalDelay');
    var applyDelayBtn = document.getElementById('applyDelayBtn');

    var sessionId = 'session-' + Date.now();
    var eventSource = null;
    var isRunning = false;
    var dragStep = null;

    var activeTabName = null;
    var tabCache = {};

    // --- Local Storage persistence ---
    var STORAGE_KEY = 'workflow-editor-state';

    function saveToLocalStorage() {
        var state = {
            activeTab: activeTabName,
            tabs: tabCache,
            logLevel: logLevelSelect.value
        };
        if (activeTabName) {
            state.tabs[activeTabName] = {
                description: descriptionArea.value || null,
                steps: getSteps(),
                maxIterations: parseInt(maxIterationsInput.value, 10) || 100
            };
        }
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
        } catch (e) {}
    }

    function loadFromLocalStorage() {
        try {
            var json = localStorage.getItem(STORAGE_KEY);
            if (!json) return false;
            var state = JSON.parse(json);
            if (!state || !state.activeTab) return false;

            tabCache = state.tabs || {};
            activeTabName = state.activeTab;

            var tabNames = Object.keys(tabCache);
            if (tabNames.length > 0) {
                renderTabs(tabNames, activeTabName);
            }

            var tabData = tabCache[activeTabName];
            if (tabData) {
                loadFromSteps(activeTabName, tabData.description, tabData.steps, tabData.maxIterations);
            }

            if (state.logLevel) {
                logLevelSelect.value = state.logLevel;
            }

            return true;
        } catch (e) {
            return false;
        }
    }

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
        if (eventSource) eventSource.close();
        eventSource = new EventSource('/api/events?session=' + encodeURIComponent(sessionId));

        eventSource.onmessage = function (e) {
            try {
                var event = JSON.parse(e.data);
                appendLog(event.type, event.message);

                if (event.type === 'state-changed') {
                    loadFromServer();
                    return;
                }
                if (event.type === 'paused') {
                    resumeBtn.style.display = 'inline-block';
                }
                if (event.type === 'completed' || event.type === 'error' || event.type === 'stopped') {
                    setRunning(false);
                    resumeBtn.style.display = 'none';
                }
            } catch (err) {}
        };

        eventSource.onerror = function () {};
    }

    // --- Log ---

    function appendLog(type, message) {
        var div = document.createElement('div');
        div.className = 'log-entry ' + (type || 'info');
        if (type === 'output') {
            div.textContent = message || '';
        } else {
            var ts = new Date().toISOString();
            div.textContent = '[' + ts + '] ' + (message || '');
        }
        logOutput.appendChild(div);
        logOutput.scrollTop = logOutput.scrollHeight;
    }

    clearLogBtn.addEventListener('click', function () {
        logOutput.innerHTML = '';
    });

    logLevelSelect.addEventListener('change', function () {
        fetch('/api/log-level', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ level: logLevelSelect.value })
        }).then(function (res) { return res.json(); })
          .then(function (data) {
            appendLog('info', 'Log level set to ' + data.level);
        });
    });

    // --- Log Panel Resizer ---
    (function () {
        var startY, startHeight;
        logResizer.addEventListener('mousedown', function (e) {
            e.preventDefault();
            startY = e.clientY;
            startHeight = logSection.offsetHeight;
            logResizer.classList.add('dragging');
            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        });
        function onMouseMove(e) {
            var delta = startY - e.clientY;
            logSection.style.height = Math.max(60, startHeight + delta) + 'px';
        }
        function onMouseUp() {
            logResizer.classList.remove('dragging');
            document.removeEventListener('mousemove', onMouseMove);
            document.removeEventListener('mouseup', onMouseUp);
        }
    })();

    // --- Tab Management ---

    function renderTabs(tabs, active) {
        tabList.innerHTML = '';
        tabs.forEach(function (name) {
            var tab = document.createElement('div');
            tab.className = 'tab-item' + (name === active ? ' active' : '');

            var nameSpan = document.createElement('span');
            nameSpan.className = 'tab-name';
            nameSpan.textContent = name;
            nameSpan.title = 'Double-click to rename';

            nameSpan.addEventListener('click', function () {
                if (name !== activeTabName) switchTab(name);
            });

            nameSpan.addEventListener('dblclick', function () {
                var newName = prompt('Rename tab:', name);
                if (newName && newName !== name) renameTab(name, newName);
            });

            var closeBtn = document.createElement('button');
            closeBtn.className = 'tab-close';
            closeBtn.innerHTML = '&times;';
            closeBtn.title = 'Close tab';
            closeBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                if (confirm('Delete tab "' + name + '"?')) deleteTab(name);
            });

            tab.appendChild(nameSpan);
            tab.appendChild(closeBtn);
            tabList.appendChild(tab);
        });
        activeTabName = active;
    }

    function switchTab(name) {
        saveCurrentTabToServer(function () {
            fetch('/api/tabs/' + encodeURIComponent(name) + '/activate', {
                method: 'PUT'
            }).then(function (res) { return res.json(); })
              .then(function (data) {
                if (data.status === 'ok') {
                    activeTabName = name;
                    // data may have steps or rows
                    if (data.steps) {
                        loadFromSteps(data.name, data.description, data.steps, data.maxIterations);
                    } else if (data.rows) {
                        loadFromRows(data.name, data.rows, data.maxIterations);
                    }
                    refreshTabList();
                }
            });
        });
    }

    function saveCurrentTabToServer(callback) {
        if (!activeTabName) { if (callback) callback(); return; }
        fetch('/api/workflow', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: activeTabName,
                description: descriptionArea.value || null,
                steps: getSteps(),
                rows: getRows(),
                maxIterations: getMaxIterations()
            })
        }).then(function () {
            if (callback) callback();
        }).catch(function () {
            if (callback) callback();
        });
    }

    function refreshTabList() {
        fetch('/api/tabs').then(function (res) { return res.json(); })
          .then(function (data) { renderTabs(data.tabs, data.active); });
    }

    function createNewTab(name) {
        saveCurrentTabToServer(function () {
            fetch('/api/tabs', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: name || 'workflow' })
            }).then(function (res) { return res.json(); })
              .then(function (data) {
                if (data.status === 'ok') switchTab(data.name);
            });
        });
    }

    function deleteTab(name) {
        fetch('/api/tabs/' + encodeURIComponent(name), { method: 'DELETE' })
          .then(function (res) { return res.json(); })
          .then(function (data) {
            if (data.status === 'ok') {
                delete tabCache[name];
                loadFromServer();
            }
        });
    }

    function renameTab(oldName, newName) {
        fetch('/api/tabs/' + encodeURIComponent(oldName) + '/rename', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: newName })
        }).then(function (res) { return res.json(); })
          .then(function (data) {
            if (data.status === 'ok') {
                if (tabCache[oldName]) {
                    tabCache[data.name] = tabCache[oldName];
                    delete tabCache[oldName];
                }
                if (activeTabName === oldName) activeTabName = data.name;
                refreshTabList();
            }
        });
    }

    addTabBtn.addEventListener('click', function () {
        var name = prompt('New workflow name:', 'workflow');
        if (name) createNewTab(name);
    });

    // --- Step Group Creation ---

    function createStepGroup(from, to, label, note, actions, delay, breakpoint) {
        var group = document.createElement('div');
        group.className = 'step-group';
        group.draggable = true;

        // Header
        var header = document.createElement('div');
        header.className = 'step-header';

        var numSpan = document.createElement('span');
        numSpan.className = 'step-num';
        numSpan.textContent = '(1)';

        var statesDiv = document.createElement('span');
        statesDiv.className = 'step-states';

        var fromInput = document.createElement('input');
        fromInput.className = 'step-from';
        fromInput.type = 'text';
        fromInput.value = from || '';
        fromInput.placeholder = '0';

        var arrow = document.createElement('span');
        arrow.className = 'step-arrow';
        arrow.innerHTML = '&rarr;';

        var toInput = document.createElement('input');
        toInput.className = 'step-to';
        toInput.type = 'text';
        toInput.value = to || '';
        toInput.placeholder = '1';

        statesDiv.appendChild(fromInput);
        statesDiv.appendChild(arrow);
        statesDiv.appendChild(toInput);

        var labelInput = document.createElement('textarea');
        labelInput.className = 'step-label';
        labelInput.rows = 1;
        labelInput.value = label || '';
        labelInput.placeholder = 'label';

        var noteInput = document.createElement('textarea');
        noteInput.className = 'step-note-input';
        noteInput.rows = 1;
        noteInput.value = note || '';
        noteInput.placeholder = 'note';

        var delayInput = document.createElement('input');
        delayInput.className = 'step-delay';
        delayInput.type = 'number';
        delayInput.min = '0';
        delayInput.value = delay || 0;
        delayInput.placeholder = 'delay(ms)';
        delayInput.title = 'Delay before execution (ms)';
        delayInput.style.width = '80px';

        var bpLabel = document.createElement('label');
        bpLabel.className = 'step-bp-label';
        bpLabel.title = 'Breakpoint';
        var bpCheck = document.createElement('input');
        bpCheck.className = 'step-breakpoint';
        bpCheck.type = 'checkbox';
        bpCheck.checked = !!breakpoint;
        bpLabel.appendChild(bpCheck);
        bpLabel.appendChild(document.createTextNode(' BP'));

        var actionsBar = document.createElement('div');
        actionsBar.className = 'step-actions-bar';

        var dragHandle = document.createElement('button');
        dragHandle.innerHTML = '&#x2630;';
        dragHandle.title = 'Drag to reorder';
        dragHandle.style.cursor = 'grab';

        var addActionBtn = document.createElement('button');
        addActionBtn.textContent = '+ Action';
        addActionBtn.title = 'Add action';

        var insertStepBtn = document.createElement('button');
        insertStepBtn.textContent = '+ Step';
        insertStepBtn.title = 'Insert step below';

        var deleteStepBtn = document.createElement('button');
        deleteStepBtn.className = 'delete-step-btn';
        deleteStepBtn.innerHTML = '&times;';
        deleteStepBtn.title = 'Delete step';

        actionsBar.appendChild(dragHandle);
        actionsBar.appendChild(addActionBtn);
        actionsBar.appendChild(insertStepBtn);
        actionsBar.appendChild(deleteStepBtn);

        header.appendChild(numSpan);
        header.appendChild(statesDiv);
        header.appendChild(labelInput);
        header.appendChild(noteInput);
        header.appendChild(delayInput);
        header.appendChild(bpLabel);
        header.appendChild(actionsBar);
        group.appendChild(header);

        // Action table
        var table = document.createElement('table');
        table.className = 'action-table';

        var thead = document.createElement('thead');
        thead.innerHTML =
            '<tr>' +
            '<th class="col-act-num">#</th>' +
            '<th class="col-act-actor">actor</th>' +
            '<th class="col-act-method">method</th>' +
            '<th class="col-act-args">arguments</th>' +
            '<th class="col-act-actions"></th>' +
            '</tr>';
        table.appendChild(thead);

        var tbody = document.createElement('tbody');
        table.appendChild(tbody);
        group.appendChild(table);

        // Add actions
        if (actions && actions.length > 0) {
            for (var i = 0; i < actions.length; i++) {
                var a = actions[i];
                tbody.appendChild(createActionRow(a.actor, a.method, a.arguments));
            }
        } else {
            tbody.appendChild(createActionRow('', '', ''));
        }

        // Event: add action
        addActionBtn.addEventListener('click', function () {
            var row = createActionRow('', '', '');
            tbody.appendChild(row);
            renumberAll();
            row.querySelector('.col-act-actor').focus();
            saveToLocalStorage();
        });

        // Event: insert step below
        insertStepBtn.addEventListener('click', function () {
            var nextFrom = toInput.value;
            var newGroup = createStepGroup(nextFrom, '', '', '', null);
            group.parentNode.insertBefore(newGroup, group.nextSibling);
            renumberAll();
            newGroup.querySelector('.step-to').focus();
            saveToLocalStorage();
        });

        // Event: delete step
        deleteStepBtn.addEventListener('click', function () {
            if (stepsContainer.querySelectorAll('.step-group').length <= 1) {
                appendLog('error', 'Cannot delete the last step');
                return;
            }
            group.remove();
            renumberAll();
            saveToLocalStorage();
        });

        // Drag and drop for step groups
        group.addEventListener('dragstart', function (e) {
            dragStep = group;
            group.classList.add('dragging');
            e.dataTransfer.effectAllowed = 'move';
        });

        group.addEventListener('dragend', function () {
            group.classList.remove('dragging');
            dragStep = null;
            var allGroups = stepsContainer.querySelectorAll('.step-group');
            for (var i = 0; i < allGroups.length; i++) {
                allGroups[i].classList.remove('drag-over');
            }
            renumberAll();
            saveToLocalStorage();
        });

        group.addEventListener('dragover', function (e) {
            e.preventDefault();
            if (dragStep && dragStep !== group) {
                group.classList.add('drag-over');
            }
        });

        group.addEventListener('dragleave', function () {
            group.classList.remove('drag-over');
        });

        group.addEventListener('drop', function (e) {
            e.preventDefault();
            group.classList.remove('drag-over');
            if (dragStep && dragStep !== group) {
                stepsContainer.insertBefore(dragStep, group);
            }
        });

        // Auto-save on input changes
        header.addEventListener('input', saveToLocalStorage);

        renumberActions(tbody);

        return group;
    }

    function createActionRow(actor, method, args) {
        var tr = document.createElement('tr');

        tr.innerHTML =
            '<td class="act-num"></td>' +
            '<td><input class="col-act-actor" type="text" value="' + escapeAttr(actor || '') + '" placeholder="actor"></td>' +
            '<td><input class="col-act-method" type="text" value="' + escapeAttr(method || '') + '" placeholder="method"></td>' +
            '<td><textarea class="col-act-args" placeholder="arguments" rows="1">' + escapeAttr(args || '') + '</textarea></td>' +
            '<td><div class="act-row-actions">' +
            '<button class="act-delete-btn" title="Delete action">&times;</button>' +
            '</div></td>';

        // Auto-resize textarea
        var argsArea = tr.querySelector('.col-act-args');
        argsArea.addEventListener('input', function () { autoResize(this); });
        setTimeout(function () { autoResize(argsArea); }, 0);

        // Delete action
        tr.querySelector('.act-delete-btn').addEventListener('click', function () {
            var tbody = tr.parentNode;
            if (tbody.querySelectorAll('tr').length <= 1) {
                appendLog('error', 'Cannot delete the last action in a step');
                return;
            }
            tr.remove();
            renumberActions(tbody);
            saveToLocalStorage();
        });

        // Auto-save on input
        tr.addEventListener('input', saveToLocalStorage);

        return tr;
    }

    function renumberAll() {
        var groups = stepsContainer.querySelectorAll('.step-group');
        for (var i = 0; i < groups.length; i++) {
            groups[i].querySelector('.step-num').textContent = '(' + (i + 1) + ')';
            renumberActions(groups[i].querySelector('.action-table tbody'));
        }
    }

    function renumberActions(tbody) {
        if (!tbody) return;
        var rows = tbody.querySelectorAll('tr');
        for (var i = 0; i < rows.length; i++) {
            var numCell = rows[i].querySelector('.act-num');
            if (numCell) numCell.textContent = (i + 1);
        }
    }

    // --- Get structured steps from DOM ---

    function getSteps() {
        var groups = stepsContainer.querySelectorAll('.step-group');
        var steps = [];
        for (var i = 0; i < groups.length; i++) {
            var g = groups[i];
            var delayVal = parseInt(g.querySelector('.step-delay').value, 10) || 0;
            var bpVal = g.querySelector('.step-breakpoint').checked;
            var step = {
                from: g.querySelector('.step-from').value.trim(),
                to: g.querySelector('.step-to').value.trim(),
                label: g.querySelector('.step-label').value.trim() || null,
                note: g.querySelector('.step-note-input').value.trim() || null,
                delay: delayVal > 0 ? delayVal : null,
                breakpoint: bpVal || null,
                actions: []
            };
            var actionRows = g.querySelectorAll('.action-table tbody tr');
            for (var j = 0; j < actionRows.length; j++) {
                var tr = actionRows[j];
                var actor = tr.querySelector('.col-act-actor').value.trim();
                var method = tr.querySelector('.col-act-method').value.trim();
                var args = tr.querySelector('.col-act-args').value;
                step.actions.push({
                    actor: actor,
                    method: method,
                    arguments: args || null
                });
            }
            steps.push(step);
        }
        return steps;
    }

    // Flat rows for backward compat (used in run request)
    function getRows() {
        var steps = getSteps();
        var rows = [];
        for (var i = 0; i < steps.length; i++) {
            var step = steps[i];
            for (var j = 0; j < step.actions.length; j++) {
                var a = step.actions[j];
                rows.push({
                    from: j === 0 ? step.from : '',
                    to: j === 0 ? step.to : '',
                    actor: a.actor,
                    method: a.method,
                    arguments: a.arguments
                });
            }
        }
        return rows;
    }

    // --- Add Step ---

    addStepBtn.addEventListener('click', function () {
        var groups = stepsContainer.querySelectorAll('.step-group');
        var lastTo = '';
        if (groups.length > 0) {
            lastTo = groups[groups.length - 1].querySelector('.step-to').value;
        }
        var newGroup = createStepGroup(lastTo, '', '', '', null);
        stepsContainer.appendChild(newGroup);
        renumberAll();
        newGroup.querySelector('.step-to').focus();
        saveToLocalStorage();
    });

    // --- Run / Stop ---

    function setRunning(running) {
        isRunning = running;
        runBtn.disabled = running;
        stopBtn.disabled = !running;
    }

    runBtn.addEventListener('click', function () {
        var steps = getSteps();
        if (steps.length === 0) {
            appendLog('error', 'No valid steps to run');
            return;
        }

        setRunning(true);
        appendLog('info', 'Starting workflow: ' + (activeTabName || 'workflow'));

        fetch('/api/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: activeTabName || 'workflow',
                steps: steps,
                maxIterations: getMaxIterations(),
                logLevel: logLevelSelect.value
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

    resumeBtn.addEventListener('click', function () {
        fetch('/api/resume', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' }
        }).then(function (res) {
            return res.json();
        }).then(function (data) {
            if (data.status === 'ok') {
                resumeBtn.style.display = 'none';
                appendLog('info', 'Workflow resumed');
            }
        });
    });

    // --- Export YAML ---

    exportYamlBtn.addEventListener('click', function () {
        var steps = getSteps();
        var yaml = 'name: ' + (activeTabName || 'workflow') + '\n';
        var desc = descriptionArea.value.trim();
        if (desc) {
            yaml += 'description: "' + desc.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"\n';
        }
        yaml += 'steps:\n';

        for (var i = 0; i < steps.length; i++) {
            var step = steps[i];
            yaml += '- states: ["' + step.from + '", "' + step.to + '"]\n';
            if (step.label) {
                yaml += '  label: ' + step.label + '\n';
            }
            if (step.note) {
                yaml += '  note: "' + step.note.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"\n';
            }
            if (step.delay && step.delay > 0) {
                yaml += '  delay: ' + step.delay + '\n';
            }
            if (step.breakpoint) {
                yaml += '  breakpoint: true\n';
            }
            yaml += '  actions:\n';
            for (var j = 0; j < step.actions.length; j++) {
                var a = step.actions[j];
                yaml += '  - actor: ' + a.actor + '\n';
                yaml += '    method: ' + a.method + '\n';
                if (a.arguments) {
                    var args = a.arguments;
                    if (args.charAt(0) === '[' || args.charAt(0) === '{') {
                        yaml += '    arguments: ' + args + '\n';
                    } else {
                        yaml += '    arguments: "' + args.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"\n';
                    }
                }
            }
        }

        var blob = new Blob([yaml], { type: 'text/yaml' });
        var url = URL.createObjectURL(blob);
        var a = document.createElement('a');
        a.href = url;
        a.download = (activeTabName || 'workflow') + '.yaml';
        a.click();
        URL.revokeObjectURL(url);
        appendLog('info', 'YAML exported to file: ' + a.download);
    });

    // --- Import YAML ---

    importYamlBtn.addEventListener('click', function () {
        var input = document.createElement('input');
        input.type = 'file';
        input.accept = '.yaml,.yml';
        input.onchange = function () {
            var file = input.files[0];
            if (!file) return;
            var reader = new FileReader();
            reader.onload = function (e) {
                parseAndLoadYaml(e.target.result);
                appendLog('info', 'YAML imported from file: ' + file.name);
            };
            reader.readAsText(file);
        };
        input.click();
    });

    function parseAndLoadYaml(yaml) {
        // Send to server for parsing (uses SnakeYAML)
        fetch('/api/yaml/import', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: yaml
        }).then(function (res) { return res.json(); })
          .then(function (data) {
            if (data.status === 'ok') {
                // Reload from server to get structured data
                loadFromServer();
            } else {
                appendLog('error', 'Import failed: ' + (data.message || 'unknown error'));
            }
        }).catch(function (err) {
            appendLog('error', 'Import failed: ' + err.message);
        });
    }

    // --- Load data into UI ---

    function loadFromSteps(name, description, steps, maxIterations) {
        stepsContainer.innerHTML = '';
        activeTabName = name;
        descriptionArea.value = description || '';
        if (maxIterations) {
            maxIterationsInput.value = maxIterations;
        }

        if (steps && steps.length > 0) {
            for (var i = 0; i < steps.length; i++) {
                var s = steps[i];
                var group = createStepGroup(s.from, s.to, s.label, s.note, s.actions, s.delay, s.breakpoint);
                stepsContainer.appendChild(group);
            }
        } else {
            // Default steps
            stepsContainer.appendChild(createStepGroup('0', '1', null, null,
                [{ actor: 'log', method: 'info', arguments: 'Workflow started' }]));
            stepsContainer.appendChild(createStepGroup('1', 'end', null, null,
                [{ actor: 'log', method: 'info', arguments: 'Workflow finished' }]));
            stepsContainer.appendChild(createStepGroup('!end', 'end', 'catch-all', 'Prevent infinite loop: catch unmatched states',
                [{ actor: 'log', method: 'warn', arguments: 'Unexpected state - forcing end' }]));
        }
        renumberAll();
        saveToLocalStorage();
    }

    // Load from flat rows (backward compat)
    function loadFromRows(name, rows, maxIterations) {
        var steps = rowsToSteps(rows);
        loadFromSteps(name, null, steps, maxIterations);
    }

    // Convert flat rows to steps (client-side)
    function rowsToSteps(rows) {
        if (!rows || rows.length === 0) return [];
        var steps = [];
        var curFrom = null, curTo = null;
        var curActions = null;

        for (var i = 0; i < rows.length; i++) {
            var r = rows[i];
            var isNew = r.from && r.from !== '' && r.to && r.to !== '';
            if (isNew) {
                if (curFrom !== null && curActions !== null) {
                    steps.push({ from: curFrom, to: curTo, label: null, note: null, actions: curActions });
                }
                curFrom = r.from;
                curTo = r.to;
                curActions = [];
            }
            if (curActions !== null) {
                curActions.push({ actor: r.actor, method: r.method, arguments: r.arguments });
            }
        }
        if (curFrom !== null && curActions !== null) {
            steps.push({ from: curFrom, to: curTo, label: null, note: null, actions: curActions });
        }
        return steps;
    }

    // Legacy alias
    function loadTableFromData(name, rows, maxIterations) {
        loadFromRows(name, rows, maxIterations);
    }

    // --- Utility ---

    function escapeAttr(s) {
        return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function autoResize(el) {
        el.style.height = 'auto';
        el.style.height = Math.max(24, el.scrollHeight) + 'px';
    }

    // --- Actor Panel ---

    actorsBtn.addEventListener('click', function () {
        actorPanel.style.display = actorPanel.style.display === 'none' ? 'flex' : 'none';
        if (actorPanel.style.display === 'flex') loadActorTree();
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

                if (actor.actions && actor.actions.length > 0) {
                    body += '<div class="actor-section-label">Actions</div>';
                    body += '<div class="actor-actions-list">';
                    actor.actions.forEach(function (action) {
                        body += '<span class="actor-action-tag">' + escapeAttr(action) + '</span>';
                    });
                    body += '</div>';
                }

                if (actor.children && actor.children.length > 0) {
                    body += '<div class="actor-section-label">Children</div>';
                    body += '<div class="actor-children-list">';
                    actor.children.forEach(function (child) {
                        body += '<span class="actor-child-name">' + escapeAttr(child) + '</span> ';
                    });
                    body += '</div>';
                }

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
        fetch('/api/tabs').then(function (res) { return res.json(); })
          .then(function (tabData) {
            if (tabData.tabs && tabData.tabs.length > 0) {
                renderTabs(tabData.tabs, tabData.active);
                return fetch('/api/workflow').then(function (res) { return res.json(); })
                  .then(function (dto) {
                    if (dto.steps) {
                        loadFromSteps(dto.name, dto.description, dto.steps, dto.maxIterations);
                    } else {
                        loadFromRows(dto.name, dto.rows, dto.maxIterations);
                    }
                });
            } else {
                return fetch('/api/tabs', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: 'workflow' })
                }).then(function (res) { return res.json(); })
                  .then(function (data) {
                    activeTabName = data.name;
                    loadFromSteps(data.name, null, null, 100);
                    refreshTabList();
                });
            }
        }).catch(function () {
            if (!loadFromLocalStorage()) {
                activeTabName = 'workflow';
                renderTabs(['workflow'], 'workflow');
                loadFromSteps('workflow', null, null, 100);
            }
        });
    }

    // Auto-save on description changes
    descriptionArea.addEventListener('input', saveToLocalStorage);

    // --- Global Delay Apply ---

    applyDelayBtn.addEventListener('click', function () {
        var val = parseInt(globalDelayInput.value, 10) || 0;
        var delayInputs = stepsContainer.querySelectorAll('.step-delay');
        for (var i = 0; i < delayInputs.length; i++) {
            delayInputs[i].value = val;
        }
        appendLog('info', 'Applied delay ' + val + 'ms to all ' + delayInputs.length + ' steps');
        saveToLocalStorage();
    });

    // --- Initialize ---

    loadFromServer();
    connectSSE();

})();
