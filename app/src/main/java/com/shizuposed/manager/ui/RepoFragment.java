package com.shizuposed.manager.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.shizuposed.manager.R;
import com.shizuposed.manager.utils.Logger;

public class RepoFragment extends Fragment {

    private Logger logger;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        logger = Logger.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_repo, container, false);
        if (logger != null) logger.d("RepoFragment opened (not yet implemented)");
        return view;
    }

    public void refresh() {
        // No-op. Nothing to refresh yet.
    }
}