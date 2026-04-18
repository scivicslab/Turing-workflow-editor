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

    // (actor panel removed - merged into Actor Tree)
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
                console.log('SSE received:', e.data);
                var event = JSON.parse(e.data);
                // Handle actor-tree events for the tree panel
                if (event.type === 'actor-tree') {
                    handleActorTreeEvent(event);
                    return;
                }
                appendLog(event.type, event.message);

                // Highlight the step currently being executed
                if (isRunning && event.state) {
                    // The previous step (whose to = event.state) just finished
                    // Mark it as done, then highlight the next step (whose from = event.state)
                    var groups = stepsContainer.querySelectorAll('.step-group');
                    for (var i = 0; i < groups.length; i++) {
                        var to = groups[i].querySelector('.step-to').value.trim();
                        if (to === event.state) {
                            groups[i].classList.remove('step-running');
                            groups[i].classList.add('step-done');
                            break;
                        }
                    }
                    highlightStep(event.state);
                }

                if (event.type === 'state-changed') {
                    console.log('state-changed -> calling loadFromServer');
                    loadFromServer();
                    return;
                }
                if (event.type === 'paused') {
                    resumeBtn.style.display = 'inline-block';
                }
                if (event.type === 'completed' || event.type === 'error' || event.type === 'warning' || event.type === 'stopped') {
                    setRunning(false);
                    // Keep highlights for a moment so user can see the final state
                    setTimeout(clearAllHighlights, 2000);
                    resumeBtn.style.display = 'none';
                }
            } catch (err) {}
        };

        eventSource.onerror = function () {};
    }

    // --- Log ---

    var LOG_LEVELS = { 'OFF': 0, 'SEVERE': 1, 'WARNING': 2, 'INFO': 3, 'CONFIG': 4, 'FINE': 5, 'FINER': 6, 'FINEST': 7, 'ALL': 8 };
    var LOG_TYPE_TO_LEVEL = { 'error': 'SEVERE', 'warning': 'WARNING', 'info': 'INFO', 'fine': 'FINE', 'finer': 'FINER', 'finest': 'FINEST', 'output': 'ALL', 'completed': 'INFO', 'stopped': 'INFO', 'paused': 'INFO', 'state-changed': 'INFO' };

    var ALWAYS_SHOW = { 'output': true, 'completed': true, 'stopped': true, 'paused': true, 'warning': true, 'error': true, 'state-changed': true };

    function shouldLog(type) {
        if (ALWAYS_SHOW[type]) return true;
        var currentLevel = logLevelSelect.value || 'INFO';
        if (currentLevel === 'OFF') return false;
        var threshold = LOG_LEVELS[currentLevel] || 3;
        var typeLevel = LOG_LEVELS[LOG_TYPE_TO_LEVEL[type]] || 3;
        return typeLevel <= threshold;
    }

    function appendLog(type, message) {
        if (!shouldLog(type)) return;
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
        group.draggable = false;

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

        // Drag and drop disabled — was interfering with text selection in argument fields

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

    // --- Step Highlighting ---

    function highlightStep(fromState) {
        var groups = stepsContainer.querySelectorAll('.step-group');
        for (var i = 0; i < groups.length; i++) {
            groups[i].classList.remove('step-running');
        }
        if (!fromState) return;
        for (var i = 0; i < groups.length; i++) {
            var from = groups[i].querySelector('.step-from').value.trim();
            if (from === fromState) {
                groups[i].classList.add('step-running');
                // Scroll into view if not visible
                groups[i].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                break;
            }
        }
    }

    function markStepDone(fromState) {
        var groups = stepsContainer.querySelectorAll('.step-group');
        for (var i = 0; i < groups.length; i++) {
            var from = groups[i].querySelector('.step-from').value.trim();
            if (from === fromState) {
                groups[i].classList.add('step-done');
                break;
            }
        }
    }

    function clearAllHighlights() {
        var groups = stepsContainer.querySelectorAll('.step-group');
        for (var i = 0; i < groups.length; i++) {
            groups[i].classList.remove('step-running', 'step-done');
        }
    }

    // --- Run Parameter Dialog ---

    var paramDialog = document.getElementById('paramDialog');
    var paramFileSelect = document.getElementById('paramFileSelect');
    var paramFileLoad = document.getElementById('paramFileLoad');
    var paramPreFilled = document.getElementById('paramPreFilled');
    var paramPreFilledList = document.getElementById('paramPreFilledList');
    var paramRequired = document.getElementById('paramRequired');
    var paramRequiredList = document.getElementById('paramRequiredList');
    var paramCancel = document.getElementById('paramCancel');
    var paramRun = document.getElementById('paramRun');

    var _paramLoadedVars = {};
    var _paramPendingSteps = null;

    function extractVariables(steps) {
        var found = {};
        var re = /\$\{([^}]+)\}/g;
        steps.forEach(function (step) {
            (step.actions || []).forEach(function (action) {
                var text = (action.arguments || '') + ' ' + (action.actor || '') + ' ' + (action.method || '');
                var m;
                while ((m = re.exec(text)) !== null) {
                    if (m[1] !== 'result') found[m[1]] = true;
                    re.lastIndex = m.index + 1;
                }
            });
            // also scan label / note
            var extra = (step.label || '') + ' ' + (step.note || '');
            var m2;
            while ((m2 = re.exec(extra)) !== null) {
                if (m2[1] !== 'result') found[m2[1]] = true;
                re.lastIndex = m2.index + 1;
            }
        });
        return Object.keys(found);
    }

    var _paramMeta = {};  // {key: {description, default}}

    function renderParamDialog(allVars) {
        _paramLoadedVars = _paramLoadedVars || {};
        var preFilledKeys = Object.keys(_paramLoadedVars);
        var requiredKeys = allVars.filter(function (v) { return !_paramLoadedVars.hasOwnProperty(v); });

        // pre-filled section
        if (preFilledKeys.length > 0) {
            paramPreFilledList.innerHTML = '';
            preFilledKeys.forEach(function (k) {
                var item = document.createElement('div');
                item.className = 'param-prefilled-item';
                item.innerHTML = '<span class="param-prefilled-key">' + k + '</span>' +
                    '<span class="param-prefilled-val">' + escapeHtml(_paramLoadedVars[k]) + '</span>';
                paramPreFilledList.appendChild(item);
            });
            paramPreFilled.style.display = '';
        } else {
            paramPreFilled.style.display = 'none';
        }

        // required inputs section
        if (requiredKeys.length > 0) {
            paramRequiredList.innerHTML = '';
            requiredKeys.forEach(function (k) {
                var meta = _paramMeta[k] || {};
                var item = document.createElement('div');
                item.className = 'param-required-item';

                var label = document.createElement('span');
                label.className = 'param-required-key';
                label.textContent = k;
                item.appendChild(label);

                var input = document.createElement('textarea');
                input.className = 'param-required-input';
                input.dataset.paramKey = k;
                input.rows = 2;
                if (meta.default) input.value = meta.default;
                if (meta.description) input.placeholder = meta.description;
                item.appendChild(input);

                if (meta.description) {
                    var hint = document.createElement('div');
                    hint.className = 'param-hint';
                    hint.textContent = meta.description;
                    item.appendChild(hint);
                }

                paramRequiredList.appendChild(item);
            });
            paramRequired.style.display = '';
        } else {
            paramRequired.style.display = 'none';
        }
    }

    function escapeHtml(str) {
        return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    function openParamDialog(steps) {
        _paramPendingSteps = steps;
        _paramLoadedVars = {};
        _paramMeta = {};
        paramPreFilled.style.display = 'none';
        paramRequired.style.display = 'none';

        var allVars = extractVariables(steps);

        // Load file list and param metadata in parallel
        Promise.all([
            fetch('/api/params/files').then(function (r) { return r.json(); }).catch(function () { return []; }),
            fetch('/api/params/meta').then(function (r) { return r.json(); }).catch(function () { return {}; })
        ]).then(function (results) {
            var files = results[0];
            _paramMeta = results[1] || {};

            paramFileSelect.innerHTML = '<option value="">(none)</option>';
            files.forEach(function (f) {
                var opt = document.createElement('option');
                opt.value = f;
                opt.textContent = f;
                paramFileSelect.appendChild(opt);
            });

            renderParamDialog(allVars);
        });

        paramDialog.style.display = 'flex';
    }

    paramFileLoad.addEventListener('click', function () {
        var path = paramFileSelect.value;
        if (!path) return;
        fetch('/api/params/load?path=' + encodeURIComponent(path))
            .then(function (r) { return r.json(); })
            .then(function (data) {
                _paramLoadedVars = data.vars || {};
                renderParamDialog(extractVariables(_paramPendingSteps));
            })
            .catch(function (err) { appendLog('error', 'Failed to load param file: ' + err.message); });
    });

    paramCancel.addEventListener('click', function () {
        paramDialog.style.display = 'none';
        _paramPendingSteps = null;
    });

    paramRun.addEventListener('click', function () {
        var parameters = Object.assign({}, _paramLoadedVars);
        paramRequiredList.querySelectorAll('[data-param-key]').forEach(function (input) {
            if (input.value.trim()) parameters[input.dataset.paramKey] = input.value.trim();
        });

        paramDialog.style.display = 'none';
        var steps = _paramPendingSteps;
        _paramPendingSteps = null;

        setRunning(true);
        clearAllHighlights();
        var firstGroup = stepsContainer.querySelector('.step-group');
        if (firstGroup) firstGroup.classList.add('step-running');
        appendLog('info', 'Starting workflow: ' + (activeTabName || 'workflow'));

        fetch('/api/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: activeTabName || 'workflow',
                steps: steps,
                maxIterations: getMaxIterations(),
                logLevel: logLevelSelect.value,
                parameters: parameters
            })
        }).then(function (res) { return res.json(); })
          .then(function (data) {
            if (data.status === 'error') { appendLog('error', data.message); setRunning(false); }
          })
          .catch(function (err) { appendLog('error', 'Failed to start: ' + err.message); setRunning(false); });
    });

    runBtn.addEventListener('click', function () {
        var steps = getSteps();
        if (steps.length === 0) {
            appendLog('error', 'No valid steps to run');
            return;
        }
        openParamDialog(steps);
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
        var str = String(s || '');
        return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function autoResize(el) {
        el.style.height = 'auto';
        el.style.height = Math.max(24, el.scrollHeight) + 'px';
    }

    // --- Actor Panel ---

    // (old actor panel code removed - merged into Actor Tree)

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

    // --- Actor Tree Browser ---

    var treeBtn = document.getElementById('treeBtn');
    var pluginsBtn = document.getElementById('pluginsBtn');
    var workflowsBtn = document.getElementById('workflowsBtn');
    var sidePanel = document.getElementById('sidePanel');
    var sidePanelClose = document.getElementById('sidePanelClose');
    var sidePanelRefresh = document.getElementById('sidePanelRefresh');
    var sidePanelResizer = document.getElementById('sidePanelResizer');
    var actorTreeBody = document.getElementById('actorTreeBody');
    var actorDataPanel = document.getElementById('actorDataPanel');
    var actorDataTitle = document.getElementById('actorDataTitle');
    var actorDataContent = document.getElementById('actorDataContent');
    var pluginsBody = document.getElementById('pluginsBody');
    var workflowsBody = document.getElementById('workflowsBody');
    var selectedActorName = null;
    var actorTreeData = [];
    var treeCollapsed = {};

    var activeSideTab = 'actors';
    var sideTabs = document.querySelectorAll('.side-tab');

    function showSidePanel(tab) {
        var wasHidden = sidePanel.style.display === 'none';
        var sameTab = activeSideTab === tab && !wasHidden;

        if (sameTab) {
            // Toggle off
            sidePanel.style.display = 'none';
            sidePanelResizer.style.display = 'none';
            stopActorTreePolling();
            return;
        }

        activeSideTab = tab;
        sidePanel.style.display = 'flex';
        sidePanelResizer.style.display = 'block';

        // Update tab highlights
        for (var i = 0; i < sideTabs.length; i++) {
            sideTabs[i].classList.toggle('active', sideTabs[i].getAttribute('data-tab') === tab);
        }

        // Show correct view
        document.getElementById('sidePanelActors').style.display = tab === 'actors' ? 'flex' : 'none';
        document.getElementById('sidePanelPlugins').style.display = tab === 'plugins' ? 'flex' : 'none';
        document.getElementById('sidePanelWorkflows').style.display = tab === 'workflows' ? 'flex' : 'none';

        // Fetch data and manage polling
        if (tab === 'actors') {
            fetchActorTree();
            startActorTreePolling();
        } else {
            stopActorTreePolling();
            if (tab === 'plugins') fetchPlugins();
            else if (tab === 'workflows') fetchWorkflows();
        }
    }

    treeBtn.addEventListener('click', function () { showSidePanel('actors'); });
    pluginsBtn.addEventListener('click', function () { showSidePanel('plugins'); });
    workflowsBtn.addEventListener('click', function () { showSidePanel('workflows'); });
    sidePanelClose.addEventListener('click', function () {
        sidePanel.style.display = 'none';
        sidePanelResizer.style.display = 'none';
        stopActorTreePolling();
    });
    sidePanelRefresh.addEventListener('click', function () {
        if (activeSideTab === 'actors') fetchActorTree();
        else if (activeSideTab === 'plugins') fetchPlugins();
        else if (activeSideTab === 'workflows') fetchWorkflows();
    });

    // Tab clicks within the header
    for (var ti = 0; ti < sideTabs.length; ti++) {
        sideTabs[ti].addEventListener('click', (function (tab) {
            return function () { showSidePanel(tab); };
        })(sideTabs[ti].getAttribute('data-tab')));
    }

    // Auto-polling: refresh actor tree every 2s while Actors tab is visible
    var actorTreePollTimer = null;

    function startActorTreePolling() {
        stopActorTreePolling();
        actorTreePollTimer = setInterval(function () {
            if (sidePanel.style.display !== 'none' && activeSideTab === 'actors') {
                fetchActorTree();
            } else {
                stopActorTreePolling();
            }
        }, 2000);
    }

    function stopActorTreePolling() {
        if (actorTreePollTimer) {
            clearInterval(actorTreePollTimer);
            actorTreePollTimer = null;
        }
    }

    function fetchActorTree() {
        fetch('/api/actors/tree').then(function (r) { return r.json(); }).then(function (data) {
            actorTreeData = data;
            renderActorTree(data);
            // Re-select the actor to update detail panel if one is selected
            if (selectedActorName) {
                for (var i = 0; i < data.length; i++) {
                    if (data[i].name === selectedActorName) {
                        selectActorDetail(data[i]);
                        break;
                    }
                }
            }
        }).catch(function () {});
    }

    function buildTreeHierarchy(flatList) {
        // Build a map and find roots (parent == null or parent == "ROOT" or parent not in map)
        var map = {};
        for (var i = 0; i < flatList.length; i++) {
            map[flatList[i].name] = flatList[i];
        }
        var roots = [];
        for (var j = 0; j < flatList.length; j++) {
            var item = flatList[j];
            var parentName = item.parent;
            if (!parentName || parentName === 'ROOT' || !map[parentName]) {
                roots.push(item);
            }
        }
        return { roots: roots, map: map };
    }

    function renderActorTree(data) {
        actorTreeBody.innerHTML = '';
        var hier = buildTreeHierarchy(data);
        for (var i = 0; i < hier.roots.length; i++) {
            renderTreeNode(hier.roots[i], hier.map, 0, actorTreeBody);
        }
    }

    function renderTreeNode(actor, map, depth, container) {
        var hasChildren = actor.children && actor.children.length > 0;
        var collapsed = treeCollapsed[actor.name];

        var node = document.createElement('div');
        node.className = 'tree-node' + (actor.name === selectedActorName ? ' selected' : '');
        node.setAttribute('data-actor', actor.name);

        // Indentation
        for (var i = 0; i < depth; i++) {
            var indent = document.createElement('span');
            indent.className = 'tree-node-indent';
            node.appendChild(indent);
        }

        // Toggle arrow
        var toggle = document.createElement('span');
        toggle.className = 'tree-node-toggle';
        if (hasChildren) {
            toggle.textContent = collapsed ? '\u25B6' : '\u25BC';
            toggle.addEventListener('click', (function (name) {
                return function (e) {
                    e.stopPropagation();
                    treeCollapsed[name] = !treeCollapsed[name];
                    renderActorTree(actorTreeData);
                };
            })(actor.name));
        }
        node.appendChild(toggle);

        // Icon
        var icon = document.createElement('span');
        icon.className = 'tree-node-icon';
        icon.textContent = actor.isInterpreter ? '\u25A0' : '\u25CB';
        node.appendChild(icon);

        // Name
        var nameSpan = document.createElement('span');
        nameSpan.className = 'tree-node-name';
        nameSpan.textContent = actor.name;
        if (actor.isInterpreter && actor.workflowFile) {
            nameSpan.textContent += ' (' + actor.workflowFile + ')';
        }
        node.appendChild(nameSpan);

        // Status badge
        if (actor.isInterpreter && actor.status) {
            var statusSpan = document.createElement('span');
            var statusClass = actor.status.toLowerCase();
            statusSpan.className = 'tree-node-status ' + statusClass;
            statusSpan.textContent = actor.status === 'RUNNING' ? '\u25CF' : actor.status === 'COMPLETED' ? '\u25CB' : '\u25A0';
            statusSpan.title = actor.status;
            node.appendChild(statusSpan);
        }

        // Milestone message display (below the actor name)
        if (actor.milestoneMessage) {
            var msDiv = document.createElement('div');
            msDiv.className = 'tree-node-milestone';
            msDiv.textContent = actor.milestoneMessage;
            msDiv.title = actor.milestoneMessage;
            node.appendChild(msDiv);
        }

        // Click to select
        node.addEventListener('click', (function (a) {
            return function () { selectActor(a); };
        })(actor));

        container.appendChild(node);

        // Render children if not collapsed
        if (hasChildren && !collapsed) {
            for (var c = 0; c < actor.children.length; c++) {
                var childName = actor.children[c];
                if (map[childName]) {
                    renderTreeNode(map[childName], map, depth + 1, container);
                }
            }
        }
    }

    function selectActor(actor) {
        selectedActorName = actor.name;
        renderActorTree(actorTreeData);
        selectActorDetail(actor);
    }

    function selectActorDetail(actor) {
        actorDataPanel.style.display = 'block';
        actorDataTitle.textContent = actor.name + ' (' + actor.type + ')';

        var html = '';

        // Status info for interpreters
        if (actor.isInterpreter) {
            html += '<div class="actor-detail-section">';
            html += '<div class="actor-section-label">Status</div>';
            html += '<div class="actor-detail-value">' + escapeHtml(actor.status || 'IDLE') + '</div>';
            if (actor.currentState) {
                html += '<div class="actor-detail-value">State: <code>' + escapeHtml(actor.currentState) + '</code></div>';
            }
            if (actor.workflowFile) {
                html += '<div class="actor-detail-value">Workflow: ' + escapeHtml(actor.workflowFile) + '</div>';
            }
            html += '</div>';
        }

        // Milestone history
        if (actor.milestoneHistory && actor.milestoneHistory.length > 0) {
            html += '<div class="actor-detail-section">';
            html += '<div class="actor-section-label">Milestones</div>';
            for (var m = 0; m < actor.milestoneHistory.length; m++) {
                var entry = actor.milestoneHistory[m];
                var ts = entry.timestamp ? new Date(entry.timestamp).toLocaleTimeString() : '';
                html += '<div class="actor-detail-value" style="font-size:11px;">';
                html += '<span style="color:var(--text-secondary)">' + escapeHtml(ts) + '</span> ';
                html += escapeHtml(entry.message);
                html += '</div>';
            }
            html += '</div>';
        }

        // Actions (methods)
        if (actor.actions && actor.actions.length > 0) {
            html += '<div class="actor-detail-section">';
            html += '<div class="actor-section-label">Actions</div>';
            html += '<div class="actor-actions-list">';
            for (var i = 0; i < actor.actions.length; i++) {
                var action = actor.actions[i];
                var actionName = typeof action === 'string' ? action : action.name;
                var javadocUrl = typeof action === 'object' ? action.javadocUrl : null;
                if (javadocUrl) {
                    html += '<a class="actor-action-tag actor-action-link" href="' + escapeHtml(javadocUrl) + '" target="_blank" title="Javadoc">' + escapeHtml(actionName) + '</a>';
                } else {
                    html += '<span class="actor-action-tag">' + escapeHtml(actionName) + '</span>';
                }
            }
            html += '</div></div>';
        }

        // Children
        if (actor.children && actor.children.length > 0) {
            html += '<div class="actor-detail-section">';
            html += '<div class="actor-section-label">Children</div>';
            html += '<div class="actor-detail-value">' + actor.children.map(escapeHtml).join(', ') + '</div>';
            html += '</div>';
        }

        // Parent
        if (actor.parent) {
            html += '<div class="actor-detail-section">';
            html += '<div class="actor-section-label">Parent</div>';
            html += '<div class="actor-detail-value">' + escapeHtml(actor.parent) + '</div>';
            html += '</div>';
        }

        actorDataContent.innerHTML = html;
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    // Handle actor-tree SSE events
    function handleActorTreeEvent(event) {
        if (event.type === 'actor-tree' && event.data && event.data.actors) {
            actorTreeData = event.data.actors;
            if (sidePanel.style.display !== 'none' && activeSideTab === 'actors') {
                renderActorTree(actorTreeData);
                // Update detail panel for selected actor
                if (selectedActorName) {
                    for (var i = 0; i < actorTreeData.length; i++) {
                        if (actorTreeData[i].name === selectedActorName) {
                            selectActorDetail(actorTreeData[i]);
                            break;
                        }
                    }
                }
            }
        }
    }

    // --- Plugins Browser ---

    function fetchPlugins() {
        fetch('/api/plugins/available').then(function (r) { return r.json(); }).then(function (data) {
            renderPlugins(data);
        }).catch(function () {
            pluginsBody.innerHTML = '<div style="padding:10px;color:var(--text-secondary);">Failed to load plugins</div>';
        });
    }

    function renderPlugins(plugins) {
        pluginsBody.innerHTML = '';
        if (plugins.length === 0) {
            pluginsBody.innerHTML = '<div style="padding:10px;color:var(--text-secondary);">No plugins found in ~/.m2/repository</div>';
            return;
        }
        for (var i = 0; i < plugins.length; i++) {
            var p = plugins[i];
            var item = document.createElement('div');
            item.className = 'browse-item';

            var icon = document.createElement('span');
            icon.className = 'browse-item-icon';
            icon.textContent = '\u25A3'; // filled square with border
            item.appendChild(icon);

            var name = document.createElement('span');
            name.className = 'browse-item-name';
            name.textContent = p.artifactId;
            name.title = p.coordinate;
            item.appendChild(name);

            var meta = document.createElement('span');
            meta.className = 'browse-item-meta';
            meta.textContent = p.latestVersion || '';
            item.appendChild(meta);

            var actions = document.createElement('span');
            actions.className = 'browse-item-actions';
            var loadBtn = document.createElement('button');
            loadBtn.textContent = 'Load';
            loadBtn.title = 'Load JAR into actor system';
            loadBtn.addEventListener('click', (function (plugin) {
                return function (e) {
                    e.stopPropagation();
                    loadPlugin(plugin);
                };
            })(p));
            actions.appendChild(loadBtn);
            item.appendChild(actions);

            pluginsBody.appendChild(item);
        }
    }

    function loadPlugin(plugin) {
        if (!plugin.latestJar) {
            appendLog('error', 'No JAR found for ' + plugin.artifactId);
            return;
        }
        appendLog('info', 'Loading plugin: ' + plugin.coordinate + '...');
        fetch('/api/loader/load-jar', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: plugin.latestJar })
        }).then(function (r) { return r.json(); }).then(function (result) {
            if (result.status === 'ok') {
                appendLog('info', 'Plugin loaded: ' + plugin.artifactId + ' - ' + result.result);
            } else {
                appendLog('error', 'Failed to load plugin: ' + (result.message || result.result));
            }
        }).catch(function (err) {
            appendLog('error', 'Failed to load plugin: ' + err.message);
        });
    }

    // --- Workflows Browser ---

    function fetchWorkflows() {
        fetch('/api/workflows/available').then(function (r) { return r.json(); }).then(function (data) {
            renderWorkflows(data);
        }).catch(function () {
            workflowsBody.innerHTML = '<div style="padding:10px;color:var(--text-secondary);">Failed to load workflows</div>';
        });
    }

    function renderWorkflows(workflows) {
        workflowsBody.innerHTML = '';
        if (workflows.length === 0) {
            workflowsBody.innerHTML = '<div style="padding:10px;color:var(--text-secondary);">No workflow files found</div>';
            return;
        }

        // Group by project
        var groups = {};
        for (var i = 0; i < workflows.length; i++) {
            var wf = workflows[i];
            var proj = wf.project || 'unknown';
            if (!groups[proj]) groups[proj] = [];
            groups[proj].push(wf);
        }

        var projNames = Object.keys(groups).sort();
        for (var g = 0; g < projNames.length; g++) {
            var projName = projNames[g];
            var label = document.createElement('div');
            label.className = 'browse-group-label';
            label.textContent = projName;
            workflowsBody.appendChild(label);

            var items = groups[projName];
            for (var j = 0; j < items.length; j++) {
                var w = items[j];
                var item = document.createElement('div');
                item.className = 'browse-item';

                var icon = document.createElement('span');
                icon.className = 'browse-item-icon';
                icon.textContent = '\u25B7'; // triangle
                item.appendChild(icon);

                var name = document.createElement('span');
                name.className = 'browse-item-name';
                name.textContent = w.name;
                name.title = w.path;
                item.appendChild(name);

                var meta = document.createElement('span');
                meta.className = 'browse-item-meta';
                meta.textContent = w.file;
                item.appendChild(meta);

                var actions = document.createElement('span');
                actions.className = 'browse-item-actions';
                var openBtn = document.createElement('button');
                openBtn.textContent = 'Open';
                openBtn.title = 'Load workflow into editor';
                openBtn.addEventListener('click', (function (workflow) {
                    return function (e) {
                        e.stopPropagation();
                        openWorkflow(workflow);
                    };
                })(w));
                actions.appendChild(openBtn);
                item.appendChild(actions);

                workflowsBody.appendChild(item);
            }
        }
    }

    function openWorkflow(workflow) {
        appendLog('info', 'Loading workflow: ' + workflow.name + ' from ' + workflow.path + '...');
        fetch('/api/workflows/load', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: workflow.path })
        }).then(function (r) { return r.json(); }).then(function (result) {
            if (result.status === 'ok') {
                appendLog('info', 'Workflow loaded: ' + result.name + ' (' + result.stepCount + ' steps)');
                // Reload editor with new workflow
                loadFromServer();
            } else {
                appendLog('error', 'Failed to load workflow: ' + result.message);
            }
        }).catch(function (err) {
            appendLog('error', 'Failed to load workflow: ' + err.message);
        });
    }

    // Tree panel horizontal resizer
    (function () {
        var isResizing = false;
        sidePanelResizer.addEventListener('mousedown', function (e) {
            isResizing = true;
            e.preventDefault();
        });
        document.addEventListener('mousemove', function (e) {
            if (!isResizing) return;
            var newWidth = e.clientX;
            if (newWidth < 160) newWidth = 160;
            if (newWidth > 500) newWidth = 500;
            sidePanel.style.width = newWidth + 'px';
        });
        document.addEventListener('mouseup', function () { isResizing = false; });
    })();

    // --- Initialize ---

    loadFromServer();
    connectSSE();

})();
