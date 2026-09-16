package com.shizuposed.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.shizuposed.manager.R;
import com.shizuposed.manager.ShizukuHelper;
import com.shizuposed.manager.adapter.IconResolver;
import com.shizuposed.manager.core.ModuleLoader;
import com.shizuposed.manager.model.ModuleInfo;
import com.shizuposed.manager.service.ShizuPosedService;
import com.shizuposed.manager.utils.Logger;

import java.util.ArrayList;
import java.util.List;

public class ModuleDetailSheet extends BottomSheetDialogFragment {

    private static final String ARG_PACKAGE = "packageName";

    private ModuleInfo module;
    private Logger logger;
    private PackageManager pm;
    private ModuleLoader moduleLoader;

    // Set in onViewCreated, cleared in onDestroyView.
    private volatile boolean viewReady = false;

    public static ModuleDetailSheet newInstance(String packageName) {
        ModuleDetailSheet s = new ModuleDetailSheet();
        Bundle b = new Bundle();
        b.putString(ARG_PACKAGE, packageName);
        s.setArguments(b);
        return s;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
        pm = context.getPackageManager();
        moduleLoader = ModuleLoader.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_module_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewReady = true;

        String pkg = getArguments() != null ? getArguments().getString(ARG_PACKAGE) : null;
        if (pkg == null) {
            if (logger != null) logger.w("ModuleDetailSheet: no package argument");
            dismissAllowingStateLoss();
            return;
        }

        module = moduleLoader != null ? moduleLoader.getModule(pkg) : null;

        if (module == null) {
            if (isAdded()) Toast.makeText(requireContext(),
                "Module not found", Toast.LENGTH_SHORT).show();
            dismissAllowingStateLoss();
            return;
        }

        bindHeader(view);
        bindActions(view);
        bindScope(view);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewReady = false;
    }

    // ═════════════════════════════════════════════════════════════
    // HEADER
    // ═════════════════════════════════════════════════════════════

    private void bindHeader(View v) {
        if (!viewReady || module == null) return;
        if (!isAdded()) return;

        ImageView icon = v.findViewById(R.id.ivModuleIcon);
        TextView name = v.findViewById(R.id.tvModuleName);
        TextView pkgv = v.findViewById(R.id.tvModulePackage);
        TextView status = v.findViewById(R.id.tvModuleStatus);

        if (icon != null) {
            Drawable d = IconResolver.resolve(requireContext(),
                module.packageName, module.apkPath);
            if (d != null) icon.setImageDrawable(d);
            else icon.setImageResource(R.drawable.ic_module);
        }

        if (name != null) {
            name.setText(module.name != null ? module.name : module.packageName);
        }
        if (pkgv != null) {
            pkgv.setText(module.packageName
                + (module.version != null ? " • v" + module.version : ""));
        }
        if (status != null) {
            status.setText(module.enabled ? "✅ Enabled" : "❌ Disabled");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ACTIONS
    // ═════════════════════════════════════════════════════════════

    private void bindActions(View v) {
        if (!viewReady || module == null || pm == null) return;
        if (!isAdded()) return;

        Button openApp = v.findViewById(R.id.btnOpenModuleApp);
        Button forceStop = v.findViewById(R.id.btnForceStopScoped);
        Button uninstall = v.findViewById(R.id.btnUninstallModule);

        if (openApp != null) {
            Intent launch = pm.getLaunchIntentForPackage(module.packageName);
            if (launch != null) {
                openApp.setEnabled(true);
                openApp.setOnClickListener(x -> {
                    if (!isAdded()) return;
                    try {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(),
                            "Failed to open: " + t.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                openApp.setEnabled(false);
                openApp.setText("Module has no UI");
            }
        }

        if (forceStop != null) {
            forceStop.setOnClickListener(x -> forceStopScopedApps());
        }

        if (uninstall != null) {
            uninstall.setOnClickListener(x -> {
                Fragment parent = getParentFragment();
                if (parent instanceof ModulesFragment) {
                    ((ModulesFragment) parent).uninstallModule(module);
                    dismissAllowingStateLoss();
                } else {
                    if (moduleLoader == null) return;
                    boolean removed = moduleLoader.uninstallModule(module.packageName);
                    if (removed) {
                        try {
                            if (isAdded()) {
                                Intent i = new Intent(requireContext(), ShizuPosedService.class);
                                i.setAction(ShizuPosedService.ACTION_REPUSH_MODULES);
                                requireContext().startForegroundService(i);
                            }
                        } catch (Throwable ignored) {}
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Module uninstalled", Toast.LENGTH_SHORT).show();
                    } else {
                        if (isAdded()) Toast.makeText(requireContext(),
                            "Uninstall failed", Toast.LENGTH_SHORT).show();
                    }
                    dismissAllowingStateLoss();
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SCOPE
    // ═════════════════════════════════════════════════════════════

    private void bindScope(View v) {
        if (!viewReady || module == null) return;

        TextView count = v.findViewById(R.id.tvScopeCount);
        TextView list = v.findViewById(R.id.tvScopeList);
        Button edit = v.findViewById(R.id.btnEditScope);

        List<String> apps = module.hookedApps != null
            ? new ArrayList<>(module.hookedApps)
            : new ArrayList<>();

        if (count != null) {
            count.setText("Scope (" + apps.size() + " app"
                + (apps.size() != 1 ? "s" : "") + ")");
        }

        if (list != null) {
            if (apps.isEmpty()) {
                list.setText("No apps scoped");
            } else {
                StringBuilder sb = new StringBuilder();
                int shown = Math.min(apps.size(), 6);
                for (int i = 0; i < shown; i++) {
                    sb.append("• ").append(apps.get(i));
                    if (i < shown - 1) sb.append('\n');
                }
                if (apps.size() > shown) {
                    sb.append("\n• +").append(apps.size() - shown).append(" more");
                }
                list.setText(sb.toString());
            }
        }

        if (edit != null) {
            edit.setOnClickListener(x -> {
                Fragment parent = getParentFragment();
                if (parent instanceof ModulesFragment) {
                    dismissAllowingStateLoss();
                    ((ModulesFragment) parent).openScopeEditor(module);
                } else if (isAdded()) {
                    Toast.makeText(requireContext(),
                        "Edit scope from the Modules tab",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FORCE STOP
    // ═════════════════════════════════════════════════════════════

    private void forceStopScopedApps() {
        if (!isAdded()) return;
        if (module == null || module.hookedApps == null || module.hookedApps.isEmpty()) {
            Toast.makeText(requireContext(),
                "No apps scoped — nothing to stop", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ShizukuHelper h = ShizukuHelper.getInstance(requireContext());
            if (!h.isAvailable() || !h.isAuthorized()) {
                Toast.makeText(requireContext(),
                    "Shizuku not authorized", Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder("am force-stop");
            for (String p : module.hookedApps) {
                if (p != null) sb.append(' ').append(p);
            }
            h.executeCommand(sb.toString());
            Toast.makeText(requireContext(),
                "Force-stopped " + module.hookedApps.size() + " app(s)",
                Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(requireContext(),
                "Force-stop failed: " + t.getMessage(),
                Toast.LENGTH_SHORT).show();
        }
    }
}