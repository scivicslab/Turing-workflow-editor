(function () {
    'use strict';

    var stepsContainer = document.getElementById('stepsContainer');
    var descriptionArea = document.getElementById('workflowDescription');
    var stopBtn = document.getElementById('stopBtn');
    var resumeBtn = document.getElementById('resumeBtn');
    var paramExecute = document.getElementById('paramExecute');
    var paramNoParams = document.getElementById('paramNoParams');
    var saveBtn = document.getElementById('saveBtn');
    var exportYamlBtn = document.getElementById('exportYamlBtn');
    var importYamlBtn = document.getElementById('importYamlBtn');
    var newWorkflowBtn = document.getElementById('newWorkflowBtn');
    var importYamlHeaderBtn = document.getElementById('importYamlHeaderBtn');
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

                if (event.type === 'state-changed') {
                    console.log('state-changed -> calling loadFromServer');
                    loadFromServer();
                    return;
                }
                if (event.type === 'queue-changed') {
                    updateQueueDisplay(event.data && event.data.items, event.data && event.data.busy);
                    return;
                }
                if (event.type === 'subworkflow-start') {
                    onSubworkflowStart(event.data && event.data.name, event.data && event.data.depth);
                    return;
                }
                if (event.type === 'subworkflow-end') {
                    onSubworkflowEnd(event.data && event.data.name, event.data && event.data.depth);
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
                    if (data.steps) {
                        loadFromSteps(data.name, data.description, data.steps, data.maxIterations);
                    } else {
                        console.error('Tab activate response missing steps');
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
            row.querySelector('.col-act-actor input').focus();
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
            '<td class="col-act-actor"><input type="text" value="' + escapeAttr(actor || '') + '" placeholder="actor"></td>' +
            '<td class="col-act-method"><input type="text" value="' + escapeAttr(method || '') + '" placeholder="method"></td>' +
            '<td class="col-act-args"><textarea placeholder="arguments" rows="1">' + escapeAttr(args || '') + '</textarea></td>' +
            '<td><div class="act-row-actions">' +
            '<button class="act-insert-btn" title="Insert action above">+</button>' +
            '<button class="act-delete-btn" title="Delete action">&times;</button>' +
            '</div></td>';

        // Auto-resize textarea
        var argsArea = tr.querySelector('.col-act-args textarea');
        argsArea.addEventListener('input', function () { autoResize(this); });
        setTimeout(function () { autoResize(argsArea); }, 0);

        // Insert action above
        tr.querySelector('.act-insert-btn').addEventListener('click', function () {
            var tbody = tr.parentNode;
            var newRow = createActionRow('', '', '');
            tbody.insertBefore(newRow, tr);
            renumberActions(tbody);
            saveToLocalStorage();
            newRow.querySelector('.col-act-actor input').focus();
        });

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
                var actor = tr.querySelector('.col-act-actor input').value.trim();
                var method = tr.querySelector('.col-act-method input').value.trim();
                var args = tr.querySelector('.col-act-args textarea').value;
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

    // --- Run / Stop ---

    function setRunning(running) {
        isRunning = running;
        stopBtn.disabled = !running;
    }

    var queueArea = document.getElementById('queueArea');
    var queueList = document.getElementById('queueList');
    var queueCount = document.getElementById('queueCount');

    function updateQueueDisplay(items, busy) {
        queueArea.style.display = 'block';
        if (!items || items.length === 0) {
            queueCount.textContent = '';
            queueList.innerHTML = '<span class="queue-empty">empty</span>';
        } else {
            queueCount.textContent = '(' + items.length + ')';
            queueList.innerHTML = '';
            items.forEach(function (item) {
                var div = document.createElement('div');
                div.className = 'queue-item';
                div.dataset.queueId = item.id;
                var name = document.createElement('span');
                name.className = 'queue-item-name';
                name.textContent = item.name;
                name.addEventListener('click', function () { openQueueItemModal(item.id); });
                var btn = document.createElement('button');
                btn.className = 'queue-item-remove';
                btn.textContent = '\u00d7';
                btn.title = 'Remove from queue';
                btn.addEventListener('click', function () {
                    fetch('/api/queue/' + item.id, { method: 'DELETE' });
                });
                div.appendChild(name);
                div.appendChild(btn);
                queueList.appendChild(div);
            });
        }
        stopBtn.disabled = !busy;
    }

    // --- Subworkflow Tab Tracking ---

    var subworkflowTabStack = []; // [{name, prevTab}]

    function onSubworkflowStart(name, depth) {
        if (!name) return;
        addLog('Subworkflow started: ' + name + ' (depth ' + depth + ')', 'info');
        subworkflowTabStack.push({ name: name, prevTab: activeTabName, depth: depth });
        // Switch to the subworkflow tab if it exists
        fetch('/api/tabs').then(function (r) { return r.json(); })
            .then(function (data) {
                var tabs = data.tabs || [];
                if (tabs.indexOf(name) >= 0) {
                    switchTab(name);
                }
            });
    }

    function onSubworkflowEnd(name, depth) {
        if (!name) return;
        addLog('Subworkflow ended: ' + name + ' (depth ' + depth + ')', 'info');
        var entry = subworkflowTabStack.pop();
        if (entry && entry.prevTab && entry.prevTab !== activeTabName) {
            switchTab(entry.prevTab);
        }
    }

    // --- Queue Item Detail Modal ---

    var queueItemModal = document.getElementById('queueItemModal');
    var queueItemModalTitle = document.getElementById('queueItemModalTitle');
    var queueItemYaml = document.getElementById('queueItemYaml');
    var queueItemUpdate = document.getElementById('queueItemUpdate');
    var queueItemModalClose = document.getElementById('queueItemModalClose');
    var queueItemClose = document.getElementById('queueItemClose');
    var currentQueueItemId = null;

    function buildCompositeYaml(item) {
        var lines = [];
        lines.push('name: ' + item.name);
        lines.push('maxIterations: ' + item.maxIterations);
        lines.push('logLevel: ' + (item.logLevel || 'INFO'));
        lines.push('workflow: |');
        (item.yaml || '').split('\n').forEach(function (l) { lines.push('  ' + l); });
        return lines.join('\n');
    }

    function parseCompositeYaml(text) {
        var result = { name: '', maxIterations: 10, logLevel: 'INFO', yaml: '' };
        var lines = text.split('\n');
        var inWorkflow = false;
        var workflowLines = [];
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (inWorkflow) {
                if (line === '' || line.startsWith('  ')) {
                    workflowLines.push(line.startsWith('  ') ? line.slice(2) : '');
                } else {
                    inWorkflow = false;
                }
            }
            if (!inWorkflow) {
                var m;
                if ((m = line.match(/^name:\s*(.+)/)))          result.name = m[1].trim();
                else if ((m = line.match(/^maxIterations:\s*(\d+)/))) result.maxIterations = parseInt(m[1]);
                else if ((m = line.match(/^logLevel:\s*(\S+)/)))  result.logLevel = m[1].trim();
                else if (/^workflow:\s*\|/.test(line))            inWorkflow = true;
            }
        }
        result.yaml = workflowLines.join('\n').replace(/\s+$/, '');
        return result;
    }

    function openQueueItemModal(id) {
        fetch('/api/queue/' + id)
            .then(function (r) { return r.json(); })
            .then(function (item) {
                currentQueueItemId = id;
                queueItemModalTitle.textContent = 'Queue Item — ' + item.name;
                queueItemYaml.value = buildCompositeYaml(item);
                queueItemYaml.readOnly = !!item.running;
                queueItemUpdate.style.display = item.running ? 'none' : '';
                queueItemModal.style.display = 'flex';
                queueItemYaml.focus();
            })
            .catch(function (e) { addLog('Failed to load queue item: ' + e, 'error'); });
    }

    function closeQueueItemModal() {
        queueItemModal.style.display = 'none';
        currentQueueItemId = null;
    }

    queueItemModalClose.addEventListener('click', closeQueueItemModal);
    queueItemClose.addEventListener('click', closeQueueItemModal);
    queueItemModal.addEventListener('click', function (e) {
        if (e.target === queueItemModal) closeQueueItemModal();
    });

    queueItemUpdate.addEventListener('click', function () {
        if (!currentQueueItemId) return;
        var parsed = parseCompositeYaml(queueItemYaml.value);
        fetch('/api/queue/' + currentQueueItemId, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(parsed)
        })
            .then(function (r) {
                if (r.ok) { closeQueueItemModal(); addLog('Queue item updated', 'step'); }
                else { r.json().then(function (d) { addLog('Update failed: ' + (d.error || r.status), 'error'); }); }
            })
            .catch(function (e) { addLog('Update error: ' + e, 'error'); });
    });

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

    // --- Run Parameter Panel ---

    var paramFileInput = document.getElementById('paramFileInput');
    var paramFileBrowse = document.getElementById('paramFileBrowse');
    var paramFileName = document.getElementById('paramFileName');
    var paramPreFilled = document.getElementById('paramPreFilled');
    var paramPreFilledList = document.getElementById('paramPreFilledList');
    var paramRequired = document.getElementById('paramRequired');
    var paramRequiredList = document.getElementById('paramRequiredList');
    var paramFilePreview = document.getElementById('paramFilePreview');
    var paramFileUnload = document.getElementById('paramFileUnload');

    var _paramLoadedVars = {};

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

        var hasAnything = preFilledKeys.length > 0 || requiredKeys.length > 0;
        paramNoParams.style.display = hasAnything ? 'none' : '';
    }

    function escapeHtml(str) {
        return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
    }

    function resetRunPanel() {
        _paramLoadedVars = {};
        _paramMeta = {};
        paramPreFilled.style.display = 'none';
        paramRequired.style.display = 'none';
        paramFileName.textContent = '(none)';
        paramFilePreview.value = '';
        paramFileUnload.style.display = 'none';
        paramFileInput.value = '';

        fetch('/api/params/meta').then(function (r) { return r.json(); }).catch(function () { return {}; })
            .then(function (meta) {
                _paramMeta = meta || {};
                var vars = extractVariables(getSteps());
                Object.keys(_paramMeta).forEach(function (k) {
                    if (vars.indexOf(k) === -1) vars.push(k);
                });
                renderParamDialog(vars);
            });
    }

    function openParamDialog() {
        resetRunPanel();
        showSidePanel('run');
    }

    paramFileBrowse.addEventListener('click', function () {
        paramFileInput.click();
    });

    paramFileInput.addEventListener('change', function () {
        var file = paramFileInput.files[0];
        if (!file) return;
        paramFileName.textContent = file.name;
        var reader = new FileReader();
        reader.onload = function (e) {
            var content = e.target.result;
            paramFilePreview.value = content;
            fetch('/api/params/parse', {
                method: 'POST',
                headers: { 'Content-Type': 'text/plain' },
                body: content
            })
                .then(function (r) { return r.json(); })
                .then(function (data) {
                    _paramLoadedVars = data.vars || {};
                    renderParamDialog(extractVariables(getSteps()));
                    paramFileUnload.style.display = 'inline-block';
                })
                .catch(function (err) { appendLog('error', 'Failed to parse param file: ' + err.message); });
        };
        reader.readAsText(file);
    });

    paramFileUnload.addEventListener('click', function () {
        _paramLoadedVars = {};
        paramFileUnload.style.display = 'none';
        paramFileName.textContent = '(none)';
        paramFilePreview.value = '';
        paramFileInput.value = '';
        renderParamDialog(extractVariables(getSteps()));
    });

    document.getElementById('sidePanelRun').addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && e.shiftKey) {
            e.preventDefault();
            paramExecute.click();
        }
    });

    paramExecute.addEventListener('click', function () {
        var parameters = Object.assign({}, _paramLoadedVars);
        paramRequiredList.querySelectorAll('[data-param-key]').forEach(function (input) {
            if (input.value.trim()) parameters[input.dataset.paramKey] = input.value.trim();
        });

        var steps = getSteps();
        if (steps.length === 0) {
            appendLog('error', 'No valid steps to run');
            return;
        }

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


    // --- Copy CLI button ---

    var copyCliBtn = document.getElementById('copyCliBtn');
    var _cliJarPath = '';
    var _cliWorkflowDir = '';
    var _cliTooltipEl = null;

    fetch('/api/system/cli-info').then(function (r) { return r.json(); }).then(function (info) {
        _cliJarPath = info.jarPath || 'turing-workflow.jar';
        _cliWorkflowDir = info.workflowDir || '';
    }).catch(function () {});

    function buildCliCommand(filePath) {
        var jar = _cliJarPath || 'turing-workflow.jar';
        var wPath = filePath || (_cliWorkflowDir ? _cliWorkflowDir + '/' + (activeTabName || 'workflow') + '.yaml' : '<workflow.yaml>');
        var maxIter = maxIterationsInput.value || '100';
        var params = Object.assign({}, _paramLoadedVars);
        document.getElementById('paramRequiredList').querySelectorAll('[data-param-key]').forEach(function (input) {
            if (input.value.trim()) params[input.dataset.paramKey] = input.value.trim();
        });
        var lines = ['java -jar ' + jar + ' run \\', '  -w ' + wPath + ' \\', '  -m ' + maxIter];
        Object.keys(params).forEach(function (k) {
            lines[lines.length - 1] += ' \\';
            lines.push('  -P ' + k + '=' + params[k]);
        });
        return lines.join('\n');
    }

    function showCliTooltip(e) {
        fetch('/api/system/cli-info').then(function (r) { return r.json(); }).then(function (info) {
            var cmd = buildCliCommand(info.filePath || '');
            if (!_cliTooltipEl) {
                _cliTooltipEl = document.createElement('div');
                _cliTooltipEl.className = 'cli-tooltip-box';
                document.body.appendChild(_cliTooltipEl);
            }
            _cliTooltipEl.textContent = cmd;
            _cliTooltipEl.style.display = 'block';
            positionCliTooltip();
        }).catch(function () {});
    }

    function positionCliTooltip() {
        if (!_cliTooltipEl) return;
        var rect = copyCliBtn.getBoundingClientRect();
        var tipH = _cliTooltipEl.offsetHeight;
        var top = rect.top - tipH - 8;
        if (top < 4) top = rect.bottom + 8;
        var left = Math.max(4, Math.min(rect.left, window.innerWidth - _cliTooltipEl.offsetWidth - 4));
        _cliTooltipEl.style.top = top + 'px';
        _cliTooltipEl.style.left = left + 'px';
    }

    function hideCliTooltip() {
        if (_cliTooltipEl) _cliTooltipEl.style.display = 'none';
    }

    copyCliBtn.addEventListener('mouseenter', showCliTooltip);
    copyCliBtn.addEventListener('mouseleave', hideCliTooltip);

    copyCliBtn.addEventListener('click', function () {
        fetch('/api/system/cli-info').then(function (r) { return r.json(); }).then(function (info) {
            var cmd = buildCliCommand(info.filePath || '');
            navigator.clipboard.writeText(cmd).then(function () {
                var orig = copyCliBtn.textContent;
                copyCliBtn.textContent = 'Copied!';
                hideCliTooltip();
                setTimeout(function () { copyCliBtn.textContent = orig; }, 1500);
            });
        }).catch(function (err) {
            appendLog('error', 'Copy CLI failed: ' + err.message);
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

    // --- Edit YAML modal ---

    var editYamlOverlay = document.getElementById('editYamlOverlay');

    // --- Catalog modal ---

    var catalogOverlay   = document.getElementById('catalogOverlay');
    var catalogCloseBtn  = document.getElementById('catalogCloseBtn');
    var catalogSearch    = document.getElementById('catalogSearch');
    var catalogDirList   = document.getElementById('catalogDirList');
    var catalogTableBody = document.getElementById('catalogTableBody');
    var catalogTable     = document.getElementById('catalogTable');
    var catalogMsg       = document.getElementById('catalogMsg');
    var catalogPager     = document.getElementById('catalogPager');

    // Each entry: {name: string, handle: FileSystemDirectoryHandle|null}
    var catalogDirs = [];
    var catalogCwd = '';
    var catalogCurrentPage = 0;
    var catalogPageSize = 10;
    var catalogCurrentQuery = '';



    catalogCloseBtn.addEventListener('click', function () {
        catalogOverlay.style.display = 'none';
    });
    catalogOverlay.addEventListener('mousedown', function (e) {
        catalogOverlay._pendingClose = (e.target === catalogOverlay);
    });
    catalogOverlay.addEventListener('mouseup', function (e) {
        if (catalogOverlay._pendingClose && e.target === catalogOverlay) {
            catalogOverlay.style.display = 'none';
        }
        catalogOverlay._pendingClose = false;
    });

    var catalogDirPicker   = document.getElementById('catalogDirPicker');
    var catalogPickerPath  = document.getElementById('catalogPickerPath');
    var catalogPickerList  = document.getElementById('catalogPickerList');
    var catalogPickerReplace = false;

    function openDirPicker(replace) {
        catalogPickerReplace = replace;
        catalogDirPicker.style.display = 'block';
        loadPickerPath('');
    }

    function loadPickerPath(path) {
        var url = '/api/fs/list' + (path ? '?path=' + encodeURIComponent(path) : '');
        fetch(url).then(function (r) { return r.json(); }).then(function (data) {
            if (data.status === 'error') return;
            catalogPickerPath.textContent = data.path;
            catalogPickerPath.title = data.path;
            catalogPickerList.innerHTML = '';
            if ((data.dirs || []).length === 0) {
                var empty = document.createElement('div');
                empty.style.cssText = 'padding:12px 16px;color:#888;font-size:0.85em;';
                empty.textContent = '(サブディレクトリなし)';
                catalogPickerList.appendChild(empty);
                return;
            }
            data.dirs.forEach(function (dir) {
                var row = document.createElement('div');
                row.style.cssText = 'display:flex;align-items:center;gap:8px;padding:6px 12px;cursor:pointer;border-bottom:1px solid var(--border-color);';
                row.innerHTML = '<span style="color:#e8a000;">📁</span>'
                    + '<span style="font-family:monospace;font-size:0.88em;flex:1;">' + dir + '</span>'
                    + '<span style="color:#aaa;font-size:0.8em;">▶</span>';
                row.addEventListener('mouseenter', function () { row.style.background = 'var(--bg-tertiary,#e8e8e8)'; });
                row.addEventListener('mouseleave', function () { row.style.background = ''; });
                row.addEventListener('click', function () {
                    loadPickerPath(data.path + '/' + dir);
                });
                catalogPickerList.appendChild(row);
            });
        });
    }

    document.getElementById('catalogPickerUp').addEventListener('click', function () {
        var cur = catalogPickerPath.textContent;
        var up = cur.replace(/\/[^/]+$/, '');
        if (up) loadPickerPath(up);
    });

    document.getElementById('catalogPickerSelect').addEventListener('click', function () {
        var abs = catalogPickerPath.textContent;
        if (!abs) return;
        if (catalogPickerReplace) catalogDirs = [];
        catalogDirs.push({name: abs, handle: null});
        catalogDirPicker.style.display = 'none';
        renderCatalogDirs();
        scanAndIndex();
    });

    document.getElementById('catalogPickerCancel').addEventListener('click', function () {
        catalogDirPicker.style.display = 'none';
    });

    document.getElementById('catalogSetDirBtn').addEventListener('click', function () {
        openDirPicker(true);
    });
    document.getElementById('catalogAddDirBtn').addEventListener('click', function () {
        openDirPicker(false);
    });

    document.getElementById('catalogSearchBtn').addEventListener('click', function () {
        catalogCurrentQuery = catalogSearch.value.trim();
        catalogCurrentPage = 0;
        loadCatalogPage();
    });

    document.getElementById('catalogSearchClear').addEventListener('click', function () {
        catalogSearch.value = '';
        catalogCurrentQuery = '';
        catalogCurrentPage = 0;
        loadCatalogPage();
    });

    catalogSearch.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
            catalogCurrentQuery = catalogSearch.value.trim();
            catalogCurrentPage = 0;
            loadCatalogPage();
        }
    });

    function pickDirectory(callback) {
        window.showDirectoryPicker()
            .then(function (handle) {
                callback({name: handle.name, handle: handle});
            })
            .catch(function () { /* cancelled */ });
    }

    function renderCatalogDirs() {
        catalogDirList.innerHTML = '';
        catalogDirs.forEach(function (entry, idx) {
            var chip = document.createElement('div');
            chip.style.cssText = 'display:inline-flex;align-items:center;gap:4px;background:var(--bg-tertiary,#e8e8e8);border-radius:3px;padding:2px 8px;font-size:0.82em;font-family:monospace;';
            var label = document.createElement('span');
            label.textContent = toRelPath(entry.name);
            chip.title = entry.name;
            chip.appendChild(label);
            var removeBtn = document.createElement('button');
            removeBtn.style.cssText = 'border:none;background:none;cursor:pointer;padding:0 2px;color:#888;';
            removeBtn.textContent = '×';
            removeBtn.addEventListener('click', (function (i) {
                return function () {
                    catalogDirs.splice(i, 1);
                    renderCatalogDirs();
                    scanAndIndex();
                };
            })(idx));
            chip.appendChild(removeBtn);
            catalogDirList.appendChild(chip);
        });
    }

    // Recursively collect *.yaml files from a FileSystemDirectoryHandle
    async function collectYamlFiles(handle, prefix) {
        var results = [];
        for await (var [name, entry] of handle.entries()) {
            if (entry.kind === 'directory') {
                var sub = await collectYamlFiles(entry, prefix + name + '/');
                results = results.concat(sub);
            } else if (name.endsWith('.yaml') || name.endsWith('.yml')) {
                results.push({file: await entry.getFile(), path: prefix + name});
            }
        }
        return results;
    }

    function scanAndIndex() {
        if (catalogDirs.length === 0) {
            catalogTable.style.display = 'none';
            catalogMsg.textContent = '';
            catalogPager.innerHTML = '';
            return;
        }
        catalogMsg.textContent = 'Scanning...';
        catalogTable.style.display = 'none';
        catalogPager.innerHTML = '';

        // Dirs with handle → client-side scan; dirs without handle → server-side scan
        var clientDirs = catalogDirs.filter(function (d) { return d.handle; });
        var serverDirs = catalogDirs.filter(function (d) { return !d.handle; });

        var serverPromise = serverDirs.length > 0
            ? fetch('/api/catalog/scan', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(serverDirs.map(function (d) { return d.name; }))
              }).then(function (r) { return r.json(); })
                .then(function (entries) {
                    return entries.map(function (e) {
                        return {filename: e.file || e.filename || '', path: e.path || '', name: e.name || '', description: e.description || '', tags: e.tags || ''};
                    });
                })
            : Promise.resolve([]);

        var clientPromise = clientDirs.length > 0
            ? Promise.all(clientDirs.map(function (d) {
                return collectYamlFiles(d.handle, d.name + '/');
              })).then(function (groups) {
                var allFiles = [].concat.apply([], groups);
                return Promise.all(allFiles.map(function (f) {
                    return f.file.text().then(function (content) {
                        // Send raw content to server for SnakeYAML parsing
                        return {filename: f.file.name, path: f.path, content: content};
                    });
                }));
              })
            : Promise.resolve([]);

        Promise.all([serverPromise, clientPromise])
            .then(function (results) {
                var all = results[0].concat(results[1]);
                return fetch('/api/catalog/index', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(all)
                });
            })
            .then(function (r) { return r.json(); })
            .then(function (result) {
                if (result.status === 'ok') {
                    catalogCurrentPage = 0;
                    catalogCurrentQuery = '';
                    catalogSearch.value = '';
                    loadCatalogPage();
                } else {
                    catalogMsg.textContent = 'Indexing error: ' + result.message;
                }
            })
            .catch(function (err) {
                catalogMsg.textContent = 'Scan error: ' + err.message;
            });
    }

    function loadCatalogPage() {
        var url = '/api/catalog/search?page=' + catalogCurrentPage + '&size=' + catalogPageSize;
        if (catalogCurrentQuery) url += '&q=' + encodeURIComponent(catalogCurrentQuery);
        fetch(url).then(function (r) { return r.json(); }).then(function (result) {
            renderCatalogTable(result);
        });
    }

    function renderCatalogTable(result) {
        var rows = result.results || [];
        var total = result.total || 0;

        if (total === 0) {
            catalogTable.style.display = 'none';
            catalogMsg.textContent = catalogCurrentQuery ? 'No results for "' + catalogCurrentQuery + '".' : 'No workflows found.';
            catalogPager.innerHTML = '';
            return;
        }

        catalogMsg.textContent = '';
        catalogTable.style.display = 'table';
        catalogTableBody.innerHTML = '';

        rows.forEach(function (entry) {
            var tr = document.createElement('tr');
            tr.style.borderBottom = '1px solid var(--border-color)';

            var tdWorkflow = document.createElement('td');
            tdWorkflow.style.cssText = 'padding:6px 8px;';
            var nameDiv = document.createElement('div');
            nameDiv.style.cssText = 'font-weight:500;';
            nameDiv.textContent = entry.name;
            var fileDiv = document.createElement('div');
            fileDiv.style.cssText = 'font-size:0.8em;color:#888;font-family:monospace;margin-top:2px;word-break:break-all;';
            var relFile = toRelPath(entry.path || entry.filename);
            fileDiv.textContent = relFile;
            fileDiv.title = entry.path || entry.filename;
            var btn = document.createElement('button');
            btn.className = 'btn btn-small catalog-import-btn';
            btn.textContent = 'Import';
            btn.style.marginTop = '4px';
            btn.addEventListener('click', (function (e) {
                return function () { importFromCatalogEntry(e); };
            })(entry));
            tdWorkflow.appendChild(nameDiv);
            tdWorkflow.appendChild(fileDiv);
            if (entry.tags) {
                var tagsDiv = document.createElement('div');
                tagsDiv.style.cssText = 'margin-top:4px;display:flex;flex-wrap:wrap;gap:3px;';
                entry.tags.split(/\s+/).filter(Boolean).forEach(function (tag) {
                    var chip = document.createElement('span');
                    chip.className = 'catalog-tag-chip';
                    chip.textContent = tag;
                    chip.addEventListener('click', function () {
                        catalogSearch.value = tag;
                        catalogCurrentQuery = tag;
                        catalogCurrentPage = 0;
                        loadCatalogPage();
                    });
                    tagsDiv.appendChild(chip);
                });
                tdWorkflow.appendChild(tagsDiv);
            }
            tdWorkflow.appendChild(btn);
            tr.appendChild(tdWorkflow);

            var tdDesc = document.createElement('td');
            tdDesc.style.cssText = 'padding:6px 8px;white-space:pre-wrap;';
            tdDesc.textContent = entry.description;
            tr.appendChild(tdDesc);

            catalogTableBody.appendChild(tr);
        });

        // Pager
        var totalPages = Math.ceil(total / catalogPageSize);
        catalogPager.innerHTML = '';
        var info = document.createElement('span');
        info.textContent = (catalogCurrentPage * catalogPageSize + 1) + '–' +
                Math.min((catalogCurrentPage + 1) * catalogPageSize, total) + ' / ' + total;
        catalogPager.appendChild(info);

        if (catalogCurrentPage > 0) {
            var prev = document.createElement('button');
            prev.className = 'btn btn-small';
            prev.textContent = '← Prev';
            prev.addEventListener('click', function () {
                catalogCurrentPage--;
                loadCatalogPage();
            });
            catalogPager.appendChild(prev);
        }
        if (catalogCurrentPage < totalPages - 1) {
            var next = document.createElement('button');
            next.className = 'btn btn-small';
            next.textContent = 'Next →';
            next.addEventListener('click', function () {
                catalogCurrentPage++;
                loadCatalogPage();
            });
            catalogPager.appendChild(next);
        }
    }

    function importFromCatalogEntry(entry) {
        var targetPath = entry.path;
        appendLog('info', 'Importing: ' + targetPath);
        if (!targetPath) { appendLog('error', 'No path for catalog entry'); return; }

        fetch('/api/catalog/file?path=' + encodeURIComponent(targetPath))
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.text();
            })
            .then(function (yaml) {
                if (!yaml || yaml.trim().length === 0) {
                    appendLog('error', 'Empty YAML returned for: ' + targetPath);
                    return;
                }
                parseAndLoadYaml(yaml);
                appendLog('info', 'Imported from catalog: ' + entry.name);
                catalogOverlay.style.display = 'none';
            })
            .catch(function (err) {
                appendLog('error', 'Catalog import failed: ' + err.message);
            });
    }

    function toRelPath(p) {
        if (catalogCwd && p && p.startsWith(catalogCwd + '/')) {
            return p.slice(catalogCwd.length + 1);
        }
        return p || '';
    }

    function openCatalog() {
        catalogOverlay.style.display = 'flex';
        catalogSearch.value = '';
        catalogCurrentQuery = '';
        requestAnimationFrame(function () {
            document.getElementById('catalogDirInput').focus();
        });
        fetch('/api/catalog/cwd')
            .then(function (r) { return r.json(); })
            .then(function (data) {
                catalogCwd = data.cwd || '';
                document.getElementById('catalogCwdDisplay').textContent = 'cwd: ' + catalogCwd;
                if (catalogDirs.length === 0) {
                    return fetch('/api/catalog/dirs')
                        .then(function (r) { return r.json(); })
                        .then(function (dirs) {
                            dirs.forEach(function (d) { catalogDirs.push({name: d, handle: null}); });
                            renderCatalogDirs();
                            scanAndIndex();
                        });
                } else {
                    renderCatalogDirs();
                    scanAndIndex();
                }
            });
    }

    // --- Edit YAML modal ---

    document.getElementById('editYamlBtn').addEventListener('click', function () {
        saveCurrentTabToServer(function () {
            fetch('/api/yaml/export')
                .then(function (r) { return r.text(); })
                .then(function (yamlText) {
                    editYamlOverlay.style.display = 'flex';
                    requestAnimationFrame(function () {
                        if (window.yamlEditorAPI) window.yamlEditorAPI.set(yamlText);
                    });
                })
                .catch(function (e) { appendLog('error', 'Failed to load YAML: ' + e.message); });
        });
    });

    document.getElementById('editYamlApply').addEventListener('click', function () {
        var yamlText = window.yamlEditorAPI ? window.yamlEditorAPI.get() : '';
        fetch('/api/yaml/import', {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: yamlText
        }).then(function (r) { return r.json(); })
          .then(function (data) {
            if (data.status === 'ok') {
                editYamlOverlay.style.display = 'none';
                loadFromServer();
                appendLog('info', 'YAML applied');
            } else {
                appendLog('error', 'Apply failed: ' + (data.message || 'unknown error'));
            }
        }).catch(function (e) { appendLog('error', 'Apply failed: ' + e.message); });
    });

    document.getElementById('editYamlCancel').addEventListener('click', function () {
        editYamlOverlay.style.display = 'none';
    });

    editYamlOverlay.addEventListener('click', function (e) {
        if (e.target === editYamlOverlay) editYamlOverlay.style.display = 'none';
    });

    // --- Export YAML ---

    saveBtn.addEventListener('click', function () {
        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving…';
        saveCurrentTabToServer(function () {
        fetch('/api/workflows/save', { method: 'POST' })
            .then(function (r) { return r.json(); })
            .then(function (data) {
                if (data.status === 'ok') {
                    saveBtn.textContent = 'Saved ✓';
                    saveBtn.style.background = 'var(--accent-green)';
                    addLog('Saved to ' + data.path, 'step');
                } else {
                    saveBtn.textContent = 'Error ✗';
                    saveBtn.style.background = 'var(--accent-red)';
                    addLog('Save failed: ' + data.message, 'error');
                }
                setTimeout(function () {
                    saveBtn.textContent = 'Save';
                    saveBtn.style.background = '';
                    saveBtn.disabled = false;
                }, 2000);
            })
            .catch(function (e) {
                saveBtn.textContent = 'Error ✗';
                saveBtn.style.background = 'var(--accent-red)';
                addLog('Save error: ' + e, 'error');
                setTimeout(function () {
                    saveBtn.textContent = 'Save';
                    saveBtn.style.background = '';
                    saveBtn.disabled = false;
                }, 2000);
            });
        }); // end saveCurrentTabToServer
    });

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

    function doImportYaml() {
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
    }

    importYamlBtn.addEventListener('click', openCatalog);
    importYamlHeaderBtn.addEventListener('click', openCatalog);

    newWorkflowBtn.addEventListener('click', function () {
        var name = prompt('Workflow name:', 'new-workflow');
        if (!name || !name.trim()) return;
        loadFromSteps(name.trim(), '', [{
            from: '0',
            to: '1',
            label: '',
            note: '',
            actions: [{ actor: '', method: '', arguments: '' }]
        }], 100);
        appendLog('info', 'New workflow created: ' + name.trim());
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

        // Refresh Run panel content if it's currently open (without toggling visibility)
        if (activeSideTab === 'run' && sidePanel.style.display !== 'none') {
            resetRunPanel(getSteps());
        }
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
                        console.error('Workflow response missing steps');
                        loadFromSteps(dto.name, dto.description, null, dto.maxIterations);
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

    function setupMenu(btnId, menuId) {
        var btn = document.getElementById(btnId);
        var menu = document.getElementById(menuId);
        btn.addEventListener('click', function (e) {
            e.stopPropagation();
            var isOpen = menu.style.display !== 'none';
            document.querySelectorAll('.header-menu').forEach(function (m) { m.style.display = 'none'; });
            menu.style.display = isOpen ? 'none' : 'block';
        });
        menu.addEventListener('click', function () { menu.style.display = 'none'; });
    }
    setupMenu('fileMenuBtn', 'fileMenu');
    document.addEventListener('click', function () {
        document.querySelectorAll('.header-menu').forEach(function (m) { m.style.display = 'none'; });
    });

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

    var activeSideTab = 'run';
    var sideTabs = document.querySelectorAll('.side-tab');

    // Show Run tab on startup
    document.getElementById('sidePanelActors').style.display = 'none';
    document.getElementById('sidePanelPlugins').style.display = 'none';
    document.getElementById('sidePanelRun').style.display = 'flex';
    document.getElementById('sidePanelQueue').style.display = 'none';

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
        document.getElementById('sidePanelRun').style.display = tab === 'run' ? 'flex' : 'none';
        document.getElementById('sidePanelQueue').style.display = tab === 'queue' ? 'flex' : 'none';

        // Fetch data and manage polling
        if (tab === 'actors') {
            fetchActorTree();
            startActorTreePolling();
        } else {
            stopActorTreePolling();
            if (tab === 'plugins') fetchPlugins();
        }
    }

    sidePanelClose.addEventListener('click', function () {
        sidePanel.style.display = 'none';
        sidePanelResizer.style.display = 'none';
        stopActorTreePolling();
    });
    sidePanelRefresh.addEventListener('click', function () {
        if (activeSideTab === 'actors') fetchActorTree();
        else if (activeSideTab === 'plugins') fetchPlugins();
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
            // Step highlighting: find the deepest RUNNING interpreter in the tree
            if (isRunning) {
                var activeInterp = null;
                // Prefer deepest RUNNING interpreter (subworkflow takes priority)
                for (var j = actorTreeData.length - 1; j >= 0; j--) {
                    if (actorTreeData[j].isInterpreter && actorTreeData[j].status === 'RUNNING') {
                        activeInterp = actorTreeData[j];
                        break;
                    }
                }
                // Fall back to first interpreter with a state
                if (!activeInterp) {
                    for (var j = 0; j < actorTreeData.length; j++) {
                        if (actorTreeData[j].isInterpreter && actorTreeData[j].currentState) {
                            activeInterp = actorTreeData[j];
                            break;
                        }
                    }
                }
                if (activeInterp) {
                    var state = activeInterp.currentState;
                    var groups = stepsContainer.querySelectorAll('.step-group');
                    for (var k = 0; k < groups.length; k++) {
                        var to = groups[k].querySelector('.step-to').value.trim();
                        if (to === state) {
                            groups[k].classList.remove('step-running');
                            groups[k].classList.add('step-done');
                            break;
                        }
                    }
                    highlightStep(state);
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
