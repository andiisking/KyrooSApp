/*
 * Copyright 2024 andiisking
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
*/

let CONFIG_PATH = "";
let SCRIPTS_DIR = "";
let SETUP_FLAG = "setup_completed";

function initializePaths() {
    if (typeof KyroosApp !== 'undefined' && KyroosApp) {
        SCRIPTS_DIR = KyroosApp.getScriptDir() || "";
        CONFIG_PATH = SCRIPTS_DIR + "/kyroos.conf";
    } else {
        SCRIPTS_DIR = "";
        CONFIG_PATH = "";
    }
}

function sanitizePackageName(pkg) {
    if (!pkg) return '';
    return pkg.toString()
        .replace(/[\\\n\r\t]/g, '')
        .replace(/package:/g, '')
        .replace(/[^a-zA-Z0-9._-]/g, '')
        .trim();
}

function removeDuplicatesAndClean(packageList) {
    if (!packageList) return [];
    
    const inputArray = Array.isArray(packageList) ? packageList : [packageList];
    
    const cleanedItems = inputArray
        .map(item => {
            if (!item) return '';
            return sanitizePackageName(item);
        })
        .filter(item => {
            if (item.length === 0) return false;
            if (!item.includes('.')) return false;
            if (item.startsWith('.')) return false;
            if (item.endsWith('.')) return false;
            const parts = item.split('.');
            return parts.every(part => part.length > 0);
        });
    
    const uniqueItems = [...new Set(cleanedItems)];
    
    return uniqueItems.sort();
}

function safeJSONParse(str) {
    try {
        return JSON.parse(str);
    } catch {
        return [];
    }
}

function debounce(func, delay) {
    let timeout;
    return (...args) => {
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(this, args), delay);
    };
}

function escapeHtml(text) {
    if (!text) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

async function runScriptFile(filename, args = "") {
    return new Promise((resolve, reject) => {
        const callbackId = 'script_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
        
        window.shellCallbacks = window.shellCallbacks || {};
        window.shellCallbacks[callbackId] = { resolve, reject };
        
        if (typeof KyroosApp !== 'undefined' && KyroosApp.runScript) {
            KyroosApp.runScript(filename, args, callbackId);
        } else {
            delete window.shellCallbacks[callbackId];
            reject(new Error("KyroosApp.runScript interface unavailable"));
            return;
        }
        
        setTimeout(() => {
            if (window.shellCallbacks[callbackId]) {
                delete window.shellCallbacks[callbackId];
                reject(new Error("Script execution timeout"));
            }
        }, 30000);
    });
}

async function execShell(command) {
    return new Promise((resolve, reject) => {
        try {
            if (typeof KyroosApp === 'undefined' || !KyroosApp) {
                reject(new Error("KyroosApp interface unavailable"));
                return;
            }

            const callbackId = 'cmd_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
            
            if (!window.shellCallbacks) {
                window.shellCallbacks = {};
            }
            
            window.shellCallbacks[callbackId] = { resolve, reject };
            KyroosApp.executeShell(command, callbackId);
            
            setTimeout(() => {
                if (window.shellCallbacks[callbackId]) {
                    window.shellCallbacks[callbackId].reject(new Error("Command timeout"));
                    delete window.shellCallbacks[callbackId];
                }
            }, 30000);
            
        } catch (e) {
            reject(e);
        }
    });
}

window.shellCallback = function(callbackId, result, isError) {
    if (window.shellCallbacks && window.shellCallbacks[callbackId]) {
        if (isError === true || isError === 'true' || isError === 1) {
            window.shellCallbacks[callbackId].reject(new Error(result || 'Unknown error'));
        } else {
            window.shellCallbacks[callbackId].resolve(result || '');
        }
        delete window.shellCallbacks[callbackId];
    }
};

function execShellSync(command) {
    if (typeof KyroosApp === 'undefined' || !KyroosApp) {
        return "Error: KyroosApp interface unavailable";
    }
    
    try {
        return KyroosApp.executeShellSync(command);
    } catch (e) {
        return "Error: " + e.message;
    }
}

async function runSetupIfNeeded() {
    const setupCompleted = localStorage.getItem(SETUP_FLAG) === 'true';
    if (setupCompleted) return;
    
    try {
        const setupScriptPath = `${SCRIPTS_DIR}/setup.sh`;
        const checkFile = await execShell(`test -f ${setupScriptPath} && echo "exists"`).catch(() => "");
        
        if (checkFile.includes("exists")) {
            await runScriptFile("setup.sh", "");
            await execShell(`rm -f ${setupScriptPath}`);
            localStorage.setItem(SETUP_FLAG, 'true');
        }
    } catch (e) {}
}

function switchTab(tabId) {
    document.querySelectorAll('.tab-section').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));
    document.getElementById(tabId).classList.add('active');
    document.getElementById('nav-' + tabId).classList.add('active');
    if (tabId === 'home') updateHomeData();
}

function toggleAppConfig(el) {
    const isEnabled = el.checked;
    localStorage.setItem('advAppConfig', isEnabled);
    const nav = document.getElementById('mainNav');
    const btn = document.getElementById('nav-apps');
    
    if (isEnabled) { 
        btn.classList.remove('hidden-tab'); 
        nav.classList.add('wide'); 
    } else { 
        btn.classList.add('hidden-tab'); 
        nav.classList.remove('wide'); 
    }
    
    if (!isEnabled && document.getElementById('apps').classList.contains('active')) {
        switchTab('home');
    }
}

function openConfigPage() { 
    document.getElementById('configPage').classList.add('open'); 
    document.getElementById('mainNav').classList.add('hidden'); 
}

function closeConfigPage() { 
    document.getElementById('configPage').classList.remove('open'); 
    document.getElementById('mainNav').classList.remove('hidden'); 
}

function openTweakPage() { 
    document.getElementById('tweakPage').classList.add('open'); 
    document.getElementById('mainNav').classList.add('hidden'); 
}

function closeTweakPage() { 
    document.getElementById('tweakPage').classList.remove('open'); 
    document.getElementById('mainNav').classList.remove('hidden'); 
}

function openDevModal() { 
    document.getElementById('devModal').classList.add('show'); 
}

function closeDevModal() { 
    document.getElementById('devModal').classList.remove('show'); 
}

async function openTgLink(url) {
    try {
        await execShell(`am start -a android.intent.action.VIEW -d "${url}"`);
    } catch (e) {}
}

let allPackages = [];
let infoCache = {};
let currentDetailPkg = "";
let currentAngleList = [];
let currentGameList = [];
let currentDevList = [];

async function loadAppIconsAndLabels(pkgs) {
    const promises = pkgs.map(async pkg => {
        if (!infoCache[pkg]) infoCache[pkg] = {};
        
        const iconBase64 = KyroosApp.getAppIconBase64(pkg);
        if (iconBase64) {
            infoCache[pkg].icon = iconBase64;
        }
        
        const label = KyroosApp.getAppLabel(pkg);
        infoCache[pkg].label = label;
    });
    
    await Promise.all(promises);
}

function openAppDetail(pkg) {
    const cleanPkg = sanitizePackageName(pkg);
    currentDetailPkg = cleanPkg;
    const cache = infoCache[cleanPkg] || {};
    document.getElementById('detailAppLabel').innerText = cache.label || cleanPkg;
    document.getElementById('detailAppPkg').innerText = cleanPkg;

    const iconBase64 = cache.icon || '';
    const detailIconBox = document.querySelector('.app-detail-hero .detail-icon-box');
    if (detailIconBox) {
        if (iconBase64) {
            detailIconBox.innerHTML = `<img src="${iconBase64}" class="app-icon-image" style="width:100%; height:100%; object-fit:cover; border-radius:24px;">`;
        } else {
            detailIconBox.innerHTML = '<i class="fas fa-android"></i>';
        }
    }

    document.getElementById('angleSwitch').checked = currentAngleList.includes(cleanPkg);
    document.getElementById('gameSwitch').checked = currentGameList.includes(cleanPkg);
    document.getElementById('devSwitch').checked = currentDevList.includes(cleanPkg);

    execShell(`cmd deviceidle whitelist | grep ${cleanPkg}`)
        .then(res => {
            document.getElementById('whitelistSwitch').checked = res.includes(cleanPkg);
        })
        .catch(() => {
            document.getElementById('whitelistSwitch').checked = false;
        });

    document.getElementById('appDetailPage').classList.add('open');
    document.getElementById('mainNav').classList.add('hidden');
}

function closeAppDetail() {
    document.getElementById('appDetailPage').classList.remove('open');
    document.getElementById('mainNav').classList.remove('hidden');
}

async function updateHomeData() {
    try {
        let chip = await execShell("getprop ro.board.platform").catch(() => "");
        if (!chip || chip.trim() === "") {
            chip = await execShell("getprop ro.product.board").catch(() => "");
        }
        document.getElementById('chipsetInfo').innerText = chip.trim() || "Unknown";

        let mem = await execShell("cat /proc/meminfo | grep MemTotal").catch(() => "");
        const memMatch = mem.match(/MemTotal:\s+(\d+)/i);
        if (memMatch) {
            const kb = parseInt(memMatch[1]);
            document.getElementById('ramInfo').innerText = (kb / 1024 / 1024).toFixed(1) + " GB";
        } else {
            document.getElementById('ramInfo').innerText = "Unknown";
        }

        let kernel = await execShell("uname -r").catch(() => "");
        if (!kernel || kernel.trim() === "") {
            kernel = await execShell("cat /proc/version").catch(() => "");
            const vMatch = kernel.match(/version (.*?) \(/);
            if (vMatch) kernel = vMatch[1];
        }
        document.getElementById('kernelInfo').innerText = kernel.trim() || "Unknown";

        checkStatus();
    } catch (e) {}
}

async function checkStatus() {
    try {
        const pid = await execShell("pgrep -f sigma").catch(() => "");
        const isRunning = pid && pid.trim().length > 0 && !pid.includes("Error");
        document.getElementById('sigmaSwitch').checked = isRunning;

        const card = document.getElementById('statusCard');
        const icon = document.querySelector('#statusIcon i');
        const title = document.getElementById('statusTitle');
        const desc = document.getElementById('statusDesc');

        if (isRunning) {
            card.classList.add('active-mode');
            if (icon) icon.className = 'fas fa-check-circle';
            title.innerText = 'Kyroos Active';
            desc.innerText = 'Sigma binary is running';
        } else {
            card.classList.remove('active-mode');
            if (icon) icon.className = 'fas fa-hourglass-half';
            title.innerText = 'Service Idle';
            desc.innerText = 'Enable in settings';
        }
    } catch (e) {}
}

async function toggleSigma(el) {
    try {
        if (el.checked) {
            await runScriptFile("sigma.sh", ""); 
        } else {
            await execShell("pkill -f sigma");
            await execShell("pkill -f sigma");
        }
        setTimeout(checkStatus, 500);
    } catch (e) {
        el.checked = !el.checked;
    }
}

let isScanning = false;

async function startAppScan() {
    if (isScanning) return;
    isScanning = true;

    document.getElementById('appInitState').style.display = 'none';
    document.getElementById('appSearch').classList.add('show');
    const container = document.getElementById('app-list-target');
    container.innerHTML = '<div class="empty-state"><i class="fas fa-spinner fa-spin"></i> Loading list...</div>';

    try {
        const raw = await execShell("pm list packages -3").catch(() => "");
        
        let rawList = [];
        
        if (raw) {
            const lines = raw.split(/\\n|\r\n|\n/);
            for (const line of lines) {
                const cleanLine = line.trim();
                if (cleanLine.startsWith('package:')) {
                    let pkg = cleanLine.substring(8).trim();
                    pkg = pkg.replace(/package:/g, '').trim();
                    
                    if (pkg && pkg.length > 0 && pkg.includes('.')) {
                        if (!pkg.startsWith("android.") && 
                            !pkg.startsWith("com.android.") && 
                            !pkg.startsWith("com.google.android.") &&
                            !pkg.startsWith("com.qualcomm.") &&
                            !pkg.startsWith("com.mediatek.") &&
                            !pkg.startsWith("com.vivo.") &&
                            !pkg.includes("overlay")) {
                            rawList.push(pkg);
                        }
                    }
                }
            }
        }

        allPackages = removeDuplicatesAndClean(rawList).slice(0, 500);
        allPackages.sort();
        
        if (allPackages.length > 0) {
            await loadAppIconsAndLabels(allPackages);
            renderAppList();
        } else {
            container.innerHTML = '<div class="empty-state">No apps found. Check Shizuku permission.</div>';
        }
    } catch (e) {
        container.innerHTML = '<div class="empty-state">Failed to load apps</div>';
    } finally {
        isScanning = false;
    }
}

function renderAppList(filter = '') {
    const container = document.getElementById('app-list-target');
    const countInfo = document.getElementById('app-count-info');
    const filterLower = filter.toLowerCase();

    const filtered = allPackages.filter(pkg =>
        pkg.toLowerCase().includes(filterLower) ||
        (infoCache[pkg]?.label || '').toLowerCase().includes(filterLower)
    );

    countInfo.textContent = `${filtered.length} apps found`;
    countInfo.style.display = 'block';

    if (filtered.length === 0) {
        container.innerHTML = '<div class="empty-state">No matching apps</div>';
        return;
    }

    const renderLimit = filter === '' ? 50 : filtered.length;
    const fragment = document.createDocumentFragment();

    filtered.slice(0, renderLimit).forEach(pkg => {
        const cached = infoCache[pkg];
        const label = cached?.label || pkg;
        const iconBase64 = cached?.icon || '';

        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => openAppDetail(pkg);

        const iconHtml = iconBase64 
            ? `<img src="${iconBase64}" class="app-icon-image">` 
            : '<i class="fas fa-android"></i>';

        div.innerHTML = `
            <div class="app-icon-placeholder">
                ${iconHtml}
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${escapeHtml(label)}</div>
                <div class="app-card-pkg">${escapeHtml(pkg)}</div>
            </div>`;
        fragment.appendChild(div);
    });
    
    container.innerHTML = '';
    container.appendChild(fragment);
}

const debouncedFilter = debounce((val) => { 
    if (allPackages.length > 0) renderAppList(val); 
}, 300);

function checkInterlock() {
    const autoEnabled = document.getElementById('powerSwitch').checked;
    const customEnabled = document.getElementById('customSaveSwitch').checked;

    const autoCard = document.getElementById('autoPowerCard');
    const customCard = document.getElementById('customPowerCard');
    const autoSw = document.getElementById('powerSwitch');
    const customSw = document.getElementById('customSaveSwitch');

    if (autoEnabled) {
        customCard.classList.add('disabled-state');
        customSw.disabled = true;
    } else {
        customCard.classList.remove('disabled-state');
        customSw.disabled = false;
    }

    if (customEnabled) {
        autoCard.classList.add('disabled-state');
        autoSw.disabled = true;
    } else {
        autoCard.classList.remove('disabled-state');
        autoSw.disabled = false;
    }
}

function handleAutoToggle(el) {
    if (el.checked) {
        document.getElementById('customSaveSwitch').checked = false;
        toggleCustomSaveUI({ checked: false });
    }
    checkInterlock();
    saveKyroosConfig();
}

function handleCustomToggle(el) {
    if (el.checked) {
        document.getElementById('powerSwitch').checked = false;
    }
    toggleCustomSaveUI(el);
    checkInterlock();
    saveKyroosConfig();
}

function toggleCustomSaveUI(el) {
    const container = document.getElementById('customSaveContainer');
    if (el.checked) container.classList.add('show'); 
    else container.classList.remove('show');
}

function handleBrutalToggle(el) {
    if (el.checked) {
        document.getElementById('confirmModal').classList.add('show');
    }
}

async function confirmBrutalAction() {
    document.getElementById('confirmModal').classList.remove('show');
    openOpapsPage();
}

function cancelBrutalAction() {
    document.getElementById('confirmModal').classList.remove('show');
    document.getElementById('brutalSwitch').checked = false;
}

async function handleDriverToggle(type) {
    const pkg = sanitizePackageName(currentDetailPkg);
    const angleEl = document.getElementById('angleSwitch');
    const gameEl = document.getElementById('gameSwitch');
    const devEl = document.getElementById('devSwitch');

    if (type === 'game' && gameEl.checked) devEl.checked = false;
    if (type === 'dev' && devEl.checked) gameEl.checked = false;

    if (angleEl.checked) { 
        if (!currentAngleList.includes(pkg)) currentAngleList.push(pkg); 
    } else { 
        currentAngleList = currentAngleList.filter(p => p !== pkg); 
    }

    if (gameEl.checked) { 
        if (!currentGameList.includes(pkg)) currentGameList.push(pkg); 
    } else { 
        currentGameList = currentGameList.filter(p => p !== pkg); 
    }

    if (devEl.checked) { 
        if (!currentDevList.includes(pkg)) currentDevList.push(pkg); 
    } else { 
        currentDevList = currentDevList.filter(p => p !== pkg); 
    }

    currentAngleList = removeDuplicatesAndClean(currentAngleList);
    currentGameList = removeDuplicatesAndClean(currentGameList);
    currentDevList = removeDuplicatesAndClean(currentDevList);

    await saveKyroosConfig();
}

async function handleWhitelistToggle(el) {
    const pkg = sanitizePackageName(currentDetailPkg);
    const cmd = el.checked ? `cmd deviceidle whitelist +${pkg}` : `cmd deviceidle whitelist -${pkg}`;
    await execShell(cmd).catch(() => {});
}

async function fetchCurrentRes() {
    try {
        const sizeRaw = await execShell("wm size");
        const sizeMatch = sizeRaw.match(/(\d+)x(\d+)/);
        if (sizeMatch) {
            document.getElementById('resW').value = sizeMatch[1];
            document.getElementById('resH').value = sizeMatch[2];
        }
    } catch (e) {}
}

function toggleResUI(el) {
    const container = document.getElementById('resContainer');
    if (el.checked) { 
        container.classList.add('show'); 
        fetchCurrentRes(); 
    } else { 
        container.classList.remove('show'); 
    }
}

async function applyResolution() {
    const w = document.getElementById('resW').value;
    const h = document.getElementById('resH').value;
    if (w && h) {
        await execShell(`wm size ${w}x${h}`).catch(() => {});
        const btn = document.querySelector('.btn-apply');
        const originalText = btn.innerHTML;
        btn.innerHTML = '<i class="fas fa-check-circle"></i> Applied!';
        setTimeout(() => { btn.innerHTML = originalText; }, 1500);
    }
}

async function resetResolution() {
    await execShell("wm size reset").catch(() => {});
    fetchCurrentRes();
}

async function applyCustomSave() {
    const btn = document.querySelector('.btn-icon-apply');
    const oldColor = btn.style.backgroundColor;
    btn.style.backgroundColor = "var(--md-secondary-container)";
    btn.style.color = "var(--md-on-secondary-container)";
    await saveKyroosConfig();
    setTimeout(() => { 
        btn.style.backgroundColor = oldColor; 
        btn.style.color = ""; 
    }, 500);
}

async function saveKyroosConfig() {
    try {
        initializePaths();
        
        currentAngleList = removeDuplicatesAndClean(currentAngleList);
        currentGameList = removeDuplicatesAndClean(currentGameList);
        currentDevList = removeDuplicatesAndClean(currentDevList);
        
        const deep = document.getElementById('deepSwitch').checked ? 'on' : 'off';
        const power = document.getElementById('powerSwitch').checked ? 'on' : 'off';
        const cache = document.getElementById('cacheSwitch').checked ? 'on' : 'off';
        const brutal = document.getElementById('brutalSwitch').checked ? 'on' : 'off';
        
        const isCustomOn = document.getElementById('customSaveSwitch').checked;
        const customValue = sanitizePackageName(document.getElementById('customSaveValue').value);
        const finalCustom = (isCustomOn && customValue) ? customValue : 'off';
        
        const angleValue = currentAngleList.length > 0 ? currentAngleList.join(',') : 'none';
        const gameValue = currentGameList.length > 0 ? currentGameList.join(',') : 'none';
        const devValue = currentDevList.length > 0 ? currentDevList.join(',') : 'none';
        
        await execShell(`rm -f ${CONFIG_PATH}`);
        
        await execShell(`echo 'deep=${deep}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'power=${power}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'chace=${cache}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'brutal=${brutal}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'cossave=${finalCustom}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'angle=${angleValue}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'game=${gameValue}' >> ${CONFIG_PATH}`);
        await execShell(`echo 'dev=${devValue}' >> ${CONFIG_PATH}`);
        
        if (KyroosApp.hasSettingsPermission && KyroosApp.hasSettingsPermission()) {
            
            if (currentAngleList.length > 0) {
                const cleanPkgs = currentAngleList.join(',');
                const cleanValues = currentAngleList.map(() => 'angle').join(',');
                
                await execShell(`settings put global angle_gl_driver_selection_pkgs "${cleanPkgs}"`);
                await execShell(`settings put global angle_gl_driver_selection_values "${cleanValues}"`);
            } else {
                await execShell(`settings delete global angle_gl_driver_selection_pkgs`);
                await execShell(`settings delete global angle_gl_driver_selection_values`);
            }
            
            if (currentGameList.length > 0) {
                const cleanGamePkgs = currentGameList.join(',');
                await execShell(`settings put global game_driver_opt_in_apps "${cleanGamePkgs}"`);
            } else {
                await execShell(`settings delete global game_driver_opt_in_apps`);
            }
            
            if (currentDevList.length > 0) {
                const cleanDevPkgs = currentDevList.join(',');
                await execShell(`settings put global game_driver_prerelease_opt_in_apps "${cleanDevPkgs}"`);
            } else {
                await execShell(`settings delete global game_driver_prerelease_opt_in_apps`);
            }
        }
        
    } catch (e) {}
}

async function loadConfig() {
    try {
        initializePaths();
        
        const configContent = await execShell(`cat ${CONFIG_PATH} 2>/dev/null || true`).catch(() => '');
        
        if (!configContent || configContent.includes('No such file')) {
            return;
        }
        
        const lines = configContent.split('\n').filter(line => line.trim().length > 0);
        
        currentAngleList = [];
        currentGameList = [];
        currentDevList = [];
        
        for (const line of lines) {
            if (!line.includes('=')) continue;
            
            const equalIndex = line.indexOf('=');
            const key = line.substring(0, equalIndex).trim();
            const value = line.substring(equalIndex + 1).trim();
            
            if (!key || !value) continue;
            
            switch(key) {
                case 'deep':
                    document.getElementById('deepSwitch').checked = value === 'on';
                    break;
                case 'power':
                    document.getElementById('powerSwitch').checked = value === 'on';
                    break;
                case 'chace':
                    document.getElementById('cacheSwitch').checked = value === 'on';
                    break;
                case 'brutal':
                    document.getElementById('brutalSwitch').checked = value === 'on';
                    break;
                case 'cossave':
                    if (value !== 'off' && value.length > 0) {
                        document.getElementById('customSaveSwitch').checked = true;
                        document.getElementById('customSaveContainer').classList.add('show');
                        document.getElementById('customSaveValue').value = value;
                    } else {
                        document.getElementById('customSaveSwitch').checked = false;
                        document.getElementById('customSaveContainer').classList.remove('show');
                    }
                    break;
                case 'angle':
                    if (value !== 'none' && value.length > 0) {
                        currentAngleList = removeDuplicatesAndClean(value.split(','));
                    }
                    break;
                case 'game':
                    if (value !== 'none' && value.length > 0) {
                        currentGameList = removeDuplicatesAndClean(value.split(','));
                    }
                    break;
                case 'dev':
                    if (value !== 'none' && value.length > 0) {
                        currentDevList = removeDuplicatesAndClean(value.split(','));
                    }
                    break;
            }
        }
        
        currentAngleList = removeDuplicatesAndClean(currentAngleList);
        currentGameList = removeDuplicatesAndClean(currentGameList);
        currentDevList = removeDuplicatesAndClean(currentDevList);
        
        checkInterlock();
        
    } catch (e) {}
}

async function repairAndRemoveDuplicates() {
    try {
        await execShell(`cp ${CONFIG_PATH} ${CONFIG_PATH}.backup.${Date.now()} 2>/dev/null || true`);
        
        const anglePkgs = await execShell(`settings get global angle_gl_driver_selection_pkgs 2>/dev/null || echo "none"`).catch(() => 'none');
        const angleVals = await execShell(`settings get global angle_gl_driver_selection_values 2>/dev/null || echo "none"`).catch(() => 'none');
        const gamePkgs = await execShell(`settings get global game_driver_opt_in_apps 2>/dev/null || echo "none"`).catch(() => 'none');
        const devPkgs = await execShell(`settings get global game_driver_prerelease_opt_in_apps 2>/dev/null || echo "none"`).catch(() => 'none');
        
        let cleanAngle = [];
        let cleanGame = [];
        let cleanDev = [];
        
        if (anglePkgs !== 'none') {
            cleanAngle = removeDuplicatesAndClean(anglePkgs.split(',').filter(p => p && p !== 'none'));
        }
        
        if (gamePkgs !== 'none') {
            cleanGame = removeDuplicatesAndClean(gamePkgs.split(',').filter(p => p && p !== 'none'));
        }
        
        if (devPkgs !== 'none') {
            cleanDev = removeDuplicatesAndClean(devPkgs.split(',').filter(p => p && p !== 'none'));
        }
        
        if (cleanAngle.length > 0) {
            await execShell(`settings put global angle_gl_driver_selection_pkgs "${cleanAngle.join(',')}"`);
            await execShell(`settings put global angle_gl_driver_selection_values "${cleanAngle.map(() => 'angle').join(',')}"`);
        }
        
        if (cleanGame.length > 0) {
            await execShell(`settings put global game_driver_opt_in_apps "${cleanGame.join(',')}"`);
        }
        
        if (cleanDev.length > 0) {
            await execShell(`settings put global game_driver_prerelease_opt_in_apps "${cleanDev.join(',')}"`);
        }
        
        currentAngleList = cleanAngle;
        currentGameList = cleanGame;
        currentDevList = cleanDev;
        
        await saveKyroosConfig();
        
    } catch (e) {}
}

let opapsPackages = [];
let selectedOpapsPkg = "";

function openOpapsPage() {
    document.getElementById('opapsPage').classList.add('open');
    document.getElementById('mainNav').classList.add('hidden');
}

function closeOpapsPage() {
    document.getElementById('opapsPage').classList.remove('open');
    document.getElementById('mainNav').classList.remove('hidden');
    document.getElementById('brutalSwitch').checked = false;
}

async function startOpapsScan() {
    if (isScanning) return;
    isScanning = true;

    const btn = document.querySelector('#opapsInitState .btn-scan');
    const originalText = btn.innerHTML;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Scanning...';
    btn.disabled = true;

    try {
        const raw = await execShell("pm list packages -3").catch(() => "");
        
        let rawList = [];
        
        if (raw) {
            const lines = raw.split(/\\n|\r\n|\n/);
            for (const line of lines) {
                const cleanLine = line.trim();
                if (cleanLine.startsWith('package:')) {
                    let pkg = cleanLine.substring(8).trim();
                    pkg = pkg.replace(/package:/g, '').trim();
                    
                    if (pkg && pkg.length > 0 && pkg.includes('.')) {
                        rawList.push(pkg);
                    }
                }
            }
        }

        opapsPackages = removeDuplicatesAndClean(rawList).slice(0, 200);
        opapsPackages.sort();
        
        if (opapsPackages.length > 0) {
            await loadAppIconsAndLabels(opapsPackages);
            document.getElementById('opapsInitState').style.display = 'none';
            document.getElementById('opapsSearch').classList.add('show');
            renderOpapsList();
        } else {
            document.getElementById('opapsInitState').innerHTML = 
                '<div class="empty-state">No user apps found. Check Shizuku.</div>';
        }
    } catch (e) {
        alert("Failed to scan apps: " + e);
    } finally {
        btn.innerHTML = originalText;
        btn.disabled = false;
        isScanning = false;
    }
}

function renderOpapsList(filter = '') {
    const container = document.getElementById('opaps-list-target');
    const countInfo = document.getElementById('opaps-count-info');
    const filterLower = filter.toLowerCase();

    const filtered = opapsPackages.filter(pkg =>
        pkg.toLowerCase().includes(filterLower) ||
        (infoCache[pkg]?.label || '').toLowerCase().includes(filterLower)
    );

    countInfo.textContent = `${filtered.length} user apps found`;
    countInfo.style.display = 'block';

    if (filtered.length === 0) {
        container.innerHTML = '<div class="empty-state">No matching apps</div>';
        return;
    }

    const fragment = document.createDocumentFragment();

    filtered.slice(0, 50).forEach(pkg => {
        const cached = infoCache[pkg];
        const label = cached?.label || pkg;
        const iconBase64 = cached?.icon || '';

        const div = document.createElement('div');
        div.className = 'app-card-item';
        div.onclick = () => executeOpapsForApp(pkg);

        const iconHtml = iconBase64 
            ? `<img src="${iconBase64}" class="app-icon-image">` 
            : '<i class="fas fa-android"></i>';

        div.innerHTML = `
            <div class="app-icon-placeholder">
                ${iconHtml}
            </div>
            <div class="app-card-info">
                <div class="app-card-title">${escapeHtml(label)}</div>
                <div class="app-card-pkg">${escapeHtml(pkg)}</div>
            </div>
            <i class="fas fa-play-circle"></i>`;
        fragment.appendChild(div);
    });
    
    container.innerHTML = '';
    container.appendChild(fragment);
}

const debouncedOpapsFilter = debounce((val) => { 
    if (opapsPackages.length > 0) renderOpapsList(val); 
}, 300);

function executeOpapsForApp(pkg) {
    const cleanPkg = sanitizePackageName(pkg);
    selectedOpapsPkg = cleanPkg;
    document.getElementById('opapsTargetPkg').innerText = cleanPkg;
    document.getElementById('opapsConfirmModal').classList.add('show');
}

function closeOpapsConfirm() {
    document.getElementById('opapsConfirmModal').classList.remove('show');
    selectedOpapsPkg = "";
}

async function runOpapsConfirmed() {
    if (!selectedOpapsPkg) return;
    const pkg = selectedOpapsPkg;

    closeOpapsConfirm();

    try {
        await runScriptFile("opaps.sh", pkg); 
        alert(`Success! Opaps applied to ${pkg}.`);
    } catch (e) {
        alert(`Failed to execute Opaps: ${e}`);
    }

    closeOpapsPage();
}

window.onload = async function () {
    initializePaths();
    
    await runSetupIfNeeded();
    
    const appConfigEnabled = localStorage.getItem('advAppConfig') === 'true';
    document.getElementById('appConfigSwitch').checked = appConfigEnabled;
    
    if (appConfigEnabled) {
        document.getElementById('nav-apps').classList.remove('hidden-tab');
        document.getElementById('mainNav').classList.add('wide');
    }
    
    updateHomeData();
    await loadConfig();
    fetchCurrentRes();
};

setInterval(() => {
    if (document.visibilityState === 'visible') {
        checkStatus();
    }
}, 5000);