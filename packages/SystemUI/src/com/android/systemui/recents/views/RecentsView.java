/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.ActivityOptions.OnAnimationStartedListener;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.AppTransitionAnimationSpec;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.HideStackActionButtonEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.ShowStackActionButtonEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.ResetBackgroundScrimEvent;
import com.android.systemui.recents.events.ui.UpdateBackgroundScrimEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.RecentsTransitionHelper.AnimationSpecComposer;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.FlingAnimationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout {

    private static final int DOCK_AREA_OVERLAY_TRANSITION_DURATION = 135;
    private static final int DEFAULT_UPDATE_SCRIM_DURATION = 200;
    private static final float DEFAULT_SCRIM_ALPHA = 0.33f;

    private final Handler mHandler;

    private TaskStack mStack;
    private TaskStackView mTaskStackView;
    private TextView mStackActionButton;
    private TextView mEmptyView;

    private boolean mAwaitingFirstLayout = true;
    private boolean mLastTaskLaunchedWasFreeform;

    @ViewDebug.ExportedProperty(category="recents")
    private Rect mSystemInsets = new Rect();
    private int mDividerSize;

    private ColorDrawable mBackgroundScrim = new ColorDrawable(Color.BLACK);
    private Animator mBackgroundScrimAnimator;

    private RecentsTransitionHelper mTransitionHelper;
    @ViewDebug.ExportedProperty(deepExport=true, prefix="touch_")
    private RecentsViewTouchHandler mTouchHandler;
    private final FlingAnimationUtils mFlingAnimationUtils;

    public RecentsView(Context context) {
        this(context, null);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);

        SystemServicesProxy ssp = Recents.getSystemServices();
        mHandler = new Handler();
        mTransitionHelper = new RecentsTransitionHelper(getContext(), mHandler);
        mDividerSize = ssp.getDockedDividerSize(context);
        mTouchHandler = new RecentsViewTouchHandler(this);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.3f);

        final float cornerRadius = context.getResources().getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius);
        LayoutInflater inflater = LayoutInflater.from(context);
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            mStackActionButton = (TextView) inflater.inflate(R.layout.recents_stack_action_button, this,
                    false);
            mStackActionButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // TODO: To be implemented
                }
            });
            addView(mStackActionButton);
            mStackActionButton.setClipToOutline(true);
            mStackActionButton.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
        }
        mEmptyView = (TextView) inflater.inflate(R.layout.recents_empty, this, false);
        addView(mEmptyView);

        setBackground(mBackgroundScrim);
    }

    /** Set/get the bsp root node */
    public void onResume(boolean isResumingFromVisible, boolean multiWindowChange,
            TaskStack stack) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();

        if (!multiWindowChange &&
                (mTaskStackView == null || !launchState.launchedReuseTaskStackViews)) {
            isResumingFromVisible = false;
            removeView(mTaskStackView);
            mTaskStackView = new TaskStackView(getContext());
            mTaskStackView.setSystemInsets(mSystemInsets);
            mStack = mTaskStackView.getStack();
            addView(mTaskStackView);
        }

        // Reset the state
        mAwaitingFirstLayout = !isResumingFromVisible;
        mLastTaskLaunchedWasFreeform = false;

        // Update the stack
        mTaskStackView.onResume(isResumingFromVisible);
        mTaskStackView.setTasks(stack, isResumingFromVisible /* notifyStackChanges */,
                true /* relayoutTaskStack */, multiWindowChange);

        if (isResumingFromVisible) {
            // If we are already visible, then restore the background scrim
            animateBackgroundScrim(DEFAULT_SCRIM_ALPHA, DEFAULT_UPDATE_SCRIM_DURATION);
        } else {
            // If we are already occluded by the app, then set the final background scrim alpha now.
            // Otherwise, defer until the enter animation completes to animate the scrim alpha with
            // the tasks for the home animation.
            if (launchState.launchedWhileDocking || launchState.launchedFromApp
                    || mStack.getTaskCount() == 0) {
                mBackgroundScrim.setAlpha((int) (DEFAULT_SCRIM_ALPHA * 255));
            } else {
                mBackgroundScrim.setAlpha(0);
            }
        }

        // Update the top level view's visibilities
        if (stack.getTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView(R.string.recents_empty_message);
        }
    }

    /**
     * Returns whether the last task launched was in the freeform stack or not.
     */
    public boolean isLastTaskLaunchedFreeform() {
        return mLastTaskLaunchedWasFreeform;
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask(int logEvent) {
        if (mTaskStackView != null) {
            Task task = mTaskStackView.getFocusedTask();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));

                if (logEvent != 0) {
                    MetricsLogger.action(getContext(), logEvent,
                            task.key.getComponent().toString());
                }
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask() {
        if (mTaskStackView != null) {
            TaskStack stack = mTaskStackView.getStack();
            Task task = stack.getLaunchTarget();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null,
                        INVALID_STACK_ID, false));
                return true;
            }
        }
        return false;
    }

    /** Launches a given task. */
    public boolean launchTask(Task task, Rect taskBounds, int destinationStack) {
        if (mTaskStackView != null) {
            // Iterate the stack views and try and find the given task.
            List<TaskView> taskViews = mTaskStackView.getTaskViews();
            int taskViewCount = taskViews.size();
            for (int j = 0; j < taskViewCount; j++) {
                TaskView tv = taskViews.get(j);
                if (tv.getTask() == task) {
                    EventBus.getDefault().send(new LaunchTaskEvent(tv, task, taskBounds,
                            destinationStack, false));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView(int msgResId) {
        mTaskStackView.setVisibility(View.INVISIBLE);
        mEmptyView.setText(msgResId);
        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.bringToFront();
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            mStackActionButton.bringToFront();
        }
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.INVISIBLE);
        mTaskStackView.setVisibility(View.VISIBLE);
        mTaskStackView.bringToFront();
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            mStackActionButton.bringToFront();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        EventBus.getDefault().register(mTouchHandler, RecentsActivity.EVENT_BUS_PRIORITY + 2);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
        EventBus.getDefault().unregister(mTouchHandler);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.measure(widthMeasureSpec, heightMeasureSpec);
        }

        // Measure the empty view to the full size of the screen
        if (mEmptyView.getVisibility() != GONE) {
            measureChild(mEmptyView, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        }

        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Measure the stack action button within the constraints of the space above the stack
            Rect actionButtonRect = mTaskStackView.mLayoutAlgorithm.mStackActionButtonRect;
            measureChild(mStackActionButton,
                    MeasureSpec.makeMeasureSpec(actionButtonRect.width(), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(actionButtonRect.height(), MeasureSpec.AT_MOST));
        }

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.layout(left, top, left + getMeasuredWidth(), top + getMeasuredHeight());
        }

        // Layout the empty view
        if (mEmptyView.getVisibility() != GONE) {
            int leftRightInsets = mSystemInsets.left + mSystemInsets.right;
            int topBottomInsets = mSystemInsets.top + mSystemInsets.bottom;
            int childWidth = mEmptyView.getMeasuredWidth();
            int childHeight = mEmptyView.getMeasuredHeight();
            int childLeft = left + Math.max(0, (right - left - leftRightInsets - childWidth)) / 2;
            int childTop = top + Math.max(0, (bottom - top - topBottomInsets - childHeight)) / 2;
            mEmptyView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }

        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Layout the stack action button such that its drawable is start-aligned with the
            // stack, vertically centered in the available space above the stack
            Rect actionButtonRect = mTaskStackView.mLayoutAlgorithm.mStackActionButtonRect;
            int buttonLeft = isLayoutRtl()
                    ? actionButtonRect.right + mStackActionButton.getPaddingStart()
                    - mStackActionButton.getMeasuredWidth()
                    : actionButtonRect.left - mStackActionButton.getPaddingStart();
            int buttonTop = actionButtonRect.top +
                    (actionButtonRect.height() - mStackActionButton.getMeasuredHeight()) / 2;
            mStackActionButton.layout(buttonLeft, buttonTop,
                    buttonLeft + mStackActionButton.getMeasuredWidth(),
                    buttonTop + mStackActionButton.getMeasuredHeight());
        }

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;

            // If launched via dragging from the nav bar, then we should translate the whole view
            // down offscreen
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            if (launchState.launchedViaDragGesture) {
                setTranslationY(getMeasuredHeight());
            } else {
                setTranslationY(0f);
            }
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        mTaskStackView.setSystemInsets(mSystemInsets);
        requestLayout();
        return insets;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mTouchHandler.onTouchEvent(ev);
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);

        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d.getAlpha() > 0) {
                d.draw(canvas);
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d == who) {
                return true;
            }
        }
        return super.verifyDrawable(who);
    }

    /**** EventBus Events ****/

    public final void onBusEvent(LaunchTaskEvent event) {
        mLastTaskLaunchedWasFreeform = event.task.isFreeformTask();
        mTransitionHelper.launchTaskFromRecents(mStack, event.task, mTaskStackView, event.taskView,
                event.screenPinningRequested, event.targetTaskBounds, event.targetTaskStack);
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        int taskViewExitToHomeDuration = TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION;
        if (RecentsDebugFlags.Static.EnableStackActionButton) {
            // Hide the stack action button
            hideStackActionButton(taskViewExitToHomeDuration, false /* translate */);
        }
        animateBackgroundScrim(0f, taskViewExitToHomeDuration);
    }

    public final void onBusEvent(DragStartEvent event) {
        updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                true /* animateAlpha */, false /* animateBounds */);
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        if (event.dropTarget == null || !(event.dropTarget instanceof TaskStack.DockState)) {
            updateVisibleDockRegions(mTouchHandler.getDockStatesForCurrentOrientation(),
                    true /* isDefaultDockState */, TaskStack.DockState.NONE.viewState.dockAreaAlpha,
                    true /* animateAlpha */, true /* animateBounds */);
        } else {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;
            updateVisibleDockRegions(new TaskStack.DockState[] {dockState},
                    false /* isDefaultDockState */, -1, true /* animateAlpha */,
                    true /* animateBounds */);
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        // Handle the case where we drop onto a dock region
        if (event.dropTarget instanceof TaskStack.DockState) {
            final TaskStack.DockState dockState = (TaskStack.DockState) event.dropTarget;

            // Hide the dock region
            updateVisibleDockRegions(null, false /* isDefaultDockState */, -1,
                    false /* animateAlpha */, false /* animateBounds */);

            TaskStackLayoutAlgorithm stackLayout = mTaskStackView.getStackAlgorithm();
            TaskStackViewScroller stackScroller = mTaskStackView.getScroller();
            TaskViewTransform tmpTransform = new TaskViewTransform();

            // We translated the view but we need to animate it back from the current layout-space
            // rect to its final layout-space rect
            int x = (int) event.taskView.getTranslationX();
            int y = (int) event.taskView.getTranslationY();
            Rect taskViewRect = new Rect(event.taskView.getLeft(), event.taskView.getTop(),
                    event.taskView.getRight(), event.taskView.getBottom());
            taskViewRect.offset(x, y);
            event.taskView.setTranslationX(0);
            event.taskView.setTranslationY(0);
            event.taskView.setLeftTopRightBottom(taskViewRect.left, taskViewRect.top,
                    taskViewRect.right, taskViewRect.bottom);

            final OnAnimationStartedListener startedListener = new OnAnimationStartedListener() {
                @Override
                public void onAnimationStarted() {
                    EventBus.getDefault().send(new DockedFirstAnimationFrameEvent());
                    mTaskStackView.getStack().removeTask(event.task, AnimationProps.IMMEDIATE,
                            true /* fromDockGesture */);
                }
            };

            // Dock the task and launch it
            SystemServicesProxy ssp = Recents.getSystemServices();
            ssp.startTaskInDockedMode(event.task.key.id, dockState.createMode);
            final Rect taskRect = getTaskRect(event.taskView);
            IAppTransitionAnimationSpecsFuture future = mTransitionHelper.getAppTransitionFuture(
                    new AnimationSpecComposer() {
                        @Override
                        public List<AppTransitionAnimationSpec> composeSpecs() {
                            return mTransitionHelper.composeDockAnimationSpec(
                                    event.taskView, taskRect);
                        }
                    });
            ssp.overridePendingAppTransitionMultiThumbFuture(future,
                    mTransitionHelper.wrapStartedListener(startedListener),
                    true /* scaleUp */);

            MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_DRAG_DROP);
        } else {
            // Animate the overlay alpha back to 0
            updateVisibleDockRegions(null, true /* isDefaultDockState */, -1,
                    true /* animateAlpha */, false /* animateBounds */);
        }
    }

    private Rect getTaskRect(TaskView taskView) {
        int[] location = taskView.getLocationOnScreen();
        int viewX = location[0];
        int viewY = location[1];
        return new Rect(viewX, viewY,
                (int) (viewX + taskView.getWidth() * taskView.getScaleX()),
                (int) (viewY + taskView.getHeight() * taskView.getScaleY()));
    }

    public final void onBusEvent(DraggingInRecentsEvent event) {
        if (mTaskStackView.getTaskViews().size() > 0) {
            setTranslationY(event.distanceFromTop - mTaskStackView.getTaskViews().get(0).getY());
        }
    }

    public final void onBusEvent(DraggingInRecentsEndedEvent event) {
        ViewPropertyAnimator animator = animate();
        if (event.velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            animator.translationY(getHeight());
            animator.withEndAction(new Runnable() {
                @Override
                public void run() {
                    WindowManagerProxy.getInstance().maximizeDockedStack();
                }
            });
            mFlingAnimationUtils.apply(animator, getTranslationY(), getHeight(), event.velocity);
        } else {
            animator.translationY(0f);
            animator.setListener(null);
            mFlingAnimationUtils.apply(animator, getTranslationY(), 0, event.velocity);
        }
        animator.start();
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (!launchState.launchedWhileDocking && !launchState.launchedFromApp
                && mStack.getTaskCount() > 0) {
            animateBackgroundScrim(DEFAULT_SCRIM_ALPHA,
                    TaskStackAnimationHelper.ENTER_FROM_HOME_TRANSLATION_DURATION);
        }
    }

    public final void onBusEvent(UpdateBackgroundScrimEvent event) {
        animateBackgroundScrim(event.alpha, DEFAULT_UPDATE_SCRIM_DURATION);
    }

    public final void onBusEvent(ResetBackgroundScrimEvent event) {
        animateBackgroundScrim(DEFAULT_SCRIM_ALPHA, DEFAULT_UPDATE_SCRIM_DURATION);
    }

    public final void onBusEvent(ShowStackActionButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        showStackActionButton(150, event.translate);
    }

    public final void onBusEvent(HideStackActionButtonEvent event) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        hideStackActionButton(100, true /* translate */);
    }

    /**
     * Shows the stack action button.
     */
    private void showStackActionButton(final int duration, final boolean translate) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        if (mStackActionButton.getVisibility() == View.INVISIBLE) {
            mStackActionButton.setVisibility(View.VISIBLE);
            mStackActionButton.setAlpha(0f);
            if (translate) {
                mStackActionButton.setTranslationY(-mStackActionButton.getMeasuredHeight() * 0.25f);
            } else {
                mStackActionButton.setTranslationY(0f);
            }
            postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    if (translate) {
                        mStackActionButton.animate()
                            .translationY(0f);
                    }
                    mStackActionButton.animate()
                            .alpha(1f)
                            .setDuration(duration)
                            .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                            .withLayer()
                            .start();
                }
            });
        }
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        hideStackActionButton(duration, translate, postAnimationTrigger);
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate,
                                       final ReferenceCountedTrigger postAnimationTrigger) {
        if (!RecentsDebugFlags.Static.EnableStackActionButton) {
            return;
        }

        if (mStackActionButton.getVisibility() == View.VISIBLE) {
            if (translate) {
                mStackActionButton.animate()
                    .translationY(-mStackActionButton.getMeasuredHeight() * 0.25f);
            }
            mStackActionButton.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mStackActionButton.setVisibility(View.INVISIBLE);
                            postAnimationTrigger.decrement();
                        }
                    })
                    .withLayer()
                    .start();
            postAnimationTrigger.increment();
        }
    }

    /**
     * Updates the dock region to match the specified dock state.
     */
    private void updateVisibleDockRegions(TaskStack.DockState[] newDockStates,
            boolean isDefaultDockState, int overrideAlpha, boolean animateAlpha,
            boolean animateBounds) {
        ArraySet<TaskStack.DockState> newDockStatesSet = Utilities.arrayToSet(newDockStates,
                new ArraySet<TaskStack.DockState>());
        ArrayList<TaskStack.DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            TaskStack.DockState dockState = visDockStates.get(i);
            TaskStack.DockState.ViewState viewState = dockState.viewState;
            if (newDockStates == null || !newDockStatesSet.contains(dockState)) {
                // This is no longer visible, so hide it
                viewState.startAnimation(null, 0, DOCK_AREA_OVERLAY_TRANSITION_DURATION,
                        Interpolators.ALPHA_OUT, animateAlpha, animateBounds);
            } else {
                // This state is now visible, update the bounds and show it
                int alpha = (overrideAlpha != -1 ? overrideAlpha : viewState.dockAreaAlpha);
                Rect bounds = isDefaultDockState
                        ? dockState.getPreDockedBounds(getMeasuredWidth(), getMeasuredHeight())
                        : dockState.getDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                        mDividerSize, mSystemInsets, getResources());
                if (viewState.dockAreaOverlay.getCallback() != this) {
                    viewState.dockAreaOverlay.setCallback(this);
                    viewState.dockAreaOverlay.setBounds(bounds);
                }
                viewState.startAnimation(bounds, alpha, DOCK_AREA_OVERLAY_TRANSITION_DURATION,
                        Interpolators.ALPHA_IN, animateAlpha, animateBounds);
            }
        }
    }

    /**
     * Animates the background scrim to the given {@param alpha}.
     */
    private void animateBackgroundScrim(float alpha, int duration) {
        Utilities.cancelAnimationWithoutCallbacks(mBackgroundScrimAnimator);
        int alphaInt = (int) (alpha * 255);
        mBackgroundScrimAnimator = ObjectAnimator.ofInt(mBackgroundScrim, Utilities.DRAWABLE_ALPHA,
                mBackgroundScrim.getAlpha(), alphaInt);
        mBackgroundScrimAnimator.setDuration(duration);
        mBackgroundScrimAnimator.setInterpolator(alphaInt > mBackgroundScrim.getAlpha()
                ? Interpolators.ALPHA_OUT
                : Interpolators.ALPHA_IN);
        mBackgroundScrimAnimator.start();
    }
}
