package com.cleveroad.loopbar.widget;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.IntDef;
import android.support.annotation.MenuRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.view.menu.MenuBuilder;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.cleveroad.loopbar.R;
import com.cleveroad.loopbar.adapter.ICategoryItem;
import com.cleveroad.loopbar.adapter.ILoopBarPagerAdapter;
import com.cleveroad.loopbar.adapter.IOperationItem;
import com.cleveroad.loopbar.adapter.SimpleCategoriesAdapter;
import com.cleveroad.loopbar.adapter.SimpleCategoriesMenuAdapter;
import com.cleveroad.loopbar.model.CategoryItem;
import com.cleveroad.loopbar.model.MockedItemsFactory;
import com.cleveroad.loopbar.util.AbstractAnimatorListener;

import java.util.ArrayList;
import java.util.List;

public class LoopBarView extends FrameLayout implements OnItemClickListener {

    public static final int SELECTION_GRAVITY_START = 0;
    public static final int SELECTION_GRAVITY_END = 1;
    private static final String TAG = LoopBarView.class.getSimpleName();

    //outside params
    private RecyclerView.Adapter<? extends RecyclerView.ViewHolder> mInputAdapter;
    private List<OnItemClickListener> mClickListeners = new ArrayList<>();
    private int mColorCodeSelectionView;

    //view settings
    private Animator mSelectionInAnimator;
    private Animator mSelectionOutAnimator;
    private int mSelectionMargin;
    private IOrientationState mOrientationState;
    private int mPlaceHolderId;
    private int mOverlaySize;
    //state settings below
    private int mCurrentItemPosition;
    @GravityAttr
    private int mSelectionGravity;

    private int mRealHidedPosition = 0;

    //views
    private FrameLayout flContainerSelected;
    private RecyclerView rvCategories;
    @Nullable
    private View overlayPlaceholder;
    private View viewColorable;

    private ChangeScrollModeAdapter.ChangeScrollModeHolder mSelectorHolder;
    private ChangeScrollModeAdapter mOuterAdapter;

    private LinearLayoutManager mLinearLayoutManager;
    private boolean mSkipNextOnLayout;
    private boolean mIndeterminateInitialized;
    private boolean mInfinite;

    private RecyclerView.OnScrollListener mIndeterminateOnScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            super.onScrolled(recyclerView, dx, dy);
            if (mLinearLayoutManager.findFirstVisibleItemPosition() == 0 || mLinearLayoutManager.findFirstVisibleItemPosition() == Integer.MAX_VALUE) {
                mLinearLayoutManager.scrollToPosition(Integer.MAX_VALUE / 2);
            }
        }
    };

    public LoopBarView(Context context) {
        super(context);
        init(context, null);
    }

    public LoopBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public LoopBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public LoopBarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    private void inflate(IOrientationState orientationState, int placeHolderId) {
        inflate(getContext(), orientationState.getLayoutId(), this);
        flContainerSelected = (FrameLayout) findViewById(R.id.flContainerSelected);
        rvCategories = (RecyclerView) findViewById(R.id.rvCategories);
        overlayPlaceholder = getRootView().findViewById(placeHolderId);
        viewColorable = getRootView().findViewById(R.id.viewColorable);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        //read customization attributes
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.LoopBarView);
        mColorCodeSelectionView = typedArray.getColor(R.styleable.LoopBarView_enls_selectionBackground,
                ContextCompat.getColor(getContext(), android.R.color.holo_blue_dark));
        int orientation = typedArray.getInteger(R.styleable.LoopBarView_enls_orientation, Orientation.ORIENTATION_HORIZONTAL);
        int selectionAnimatorInId = typedArray.getResourceId(R.styleable.LoopBarView_enls_selectionInAnimation, R.animator.enls_scale_restore);
        int selectionAnimatorOutId = typedArray.getResourceId(R.styleable.LoopBarView_enls_selectionOutAnimation, R.animator.enls_scale_small);
        mPlaceHolderId = typedArray.getResourceId(R.styleable.LoopBarView_enls_placeholderId, -1);
        @GravityAttr int selectionGravity = typedArray.getInteger(R.styleable.LoopBarView_enls_selectionGravity, SELECTION_GRAVITY_START);
        mInfinite = typedArray.getBoolean(R.styleable.LoopBarView_enls_infiniteScrolling, true);
        mSelectionGravity = selectionGravity;

        mSelectionMargin = typedArray.getDimensionPixelSize(R.styleable.LoopBarView_enls_selectionMargin,
                getResources().getDimensionPixelSize(R.dimen.enls_margin_selected_view));
        mOverlaySize = typedArray.getDimensionPixelSize(R.styleable.LoopBarView_enls_overlaySize, 0);
        typedArray.recycle();

        //check attributes you need, for example all paddings
        int[] attributes = new int[]{android.R.attr.background};
        //then obtain typed array
        typedArray = context.obtainStyledAttributes(attrs, attributes);
        int backgroundResource = typedArray.getResourceId(0, R.color.enls_default_list_background);

        mSelectionInAnimator = AnimatorInflater.loadAnimator(getContext(), selectionAnimatorInId);
        mSelectionOutAnimator = AnimatorInflater.loadAnimator(getContext(), selectionAnimatorOutId);

        //current view has two state : horizontal & vertical. State design pattern
        mOrientationState = getOrientationStateFromParam(orientation);
        inflate(mOrientationState, mPlaceHolderId);
        setBackgroundResource(backgroundResource);
        setGravity(selectionGravity);

        ColorDrawable colorDrawable = new NegativeMarginFixColorDrawable(mColorCodeSelectionView);
        viewColorable.setBackground(colorDrawable);

        mLinearLayoutManager = mOrientationState.getLayoutManager(getContext());
        rvCategories.setLayoutManager(mLinearLayoutManager);

        if (isInEditMode()) {
            setCategoriesAdapter(new SimpleCategoriesAdapter(MockedItemsFactory.getCategoryItems(getContext())));
        }

        int menuId = typedArray.getResourceId(R.styleable.LoopBarView_enls_menu, -1);
        if (menuId != -1) {
            setCategoriesAdapterFromMenu(menuId);
        }
        typedArray.recycle();
    }

    public void setGravity(@GravityAttr int selectionGravity) {
        mOrientationState.setSelectionGravity(selectionGravity);
        //note that flContainerSelected should be in FrameLayout
        FrameLayout.LayoutParams params = (LayoutParams) flContainerSelected.getLayoutParams();
        params.gravity = mOrientationState.getSelectionGravity();
        mOrientationState.setSelectionMargin(mSelectionMargin, params);
        flContainerSelected.setLayoutParams(params);
        mSelectionGravity = selectionGravity;
        invalidate();
        if (mOuterAdapter != null) {
            mOuterAdapter.setSelectedGravity(selectionGravity);
        }
    }

    public void setIsInfinite(boolean isInfinite) {
        if (mInfinite != isInfinite) {
            mInfinite = isInfinite;
            if (mOuterAdapter != null) {
                mOuterAdapter.setIsIndeterminate(isInfinite);
            }
        }
    }

    public boolean isInfinite() {
        return mInfinite;
    }

    @SuppressWarnings("unchecked assigment")
    public void setCategoriesAdapter(@NonNull RecyclerView.Adapter<? extends RecyclerView.ViewHolder> inputAdapter) {
        mInputAdapter = inputAdapter;
        this.mOuterAdapter = new ChangeScrollModeAdapter(inputAdapter);
        IOperationItem firstItem = mOuterAdapter.getItem(0);
        firstItem.setVisible(false);
        mOuterAdapter.setIsIndeterminate(mInfinite);
        mOuterAdapter.setListener(this);
        mOuterAdapter.setOrientation(mOrientationState.getOrientation());
        mOuterAdapter.setSelectedGravity(mSelectionGravity);
        rvCategories.setAdapter(mOuterAdapter);

        mSelectorHolder = (ChangeScrollModeAdapter.ChangeScrollModeHolder) mOuterAdapter.createViewHolder(rvCategories, ChangeScrollModeAdapter.VIEW_TYPE_CHANGE_SCROLL_MODE);
        // set first item to selectionView
        mSelectorHolder.bindItemWildcardHelper(inputAdapter, 0);
        mSelectorHolder.itemView.setBackgroundColor(mColorCodeSelectionView);

        flContainerSelected.addView(mSelectorHolder.itemView);

        mOrientationState.initSelectionContainer(flContainerSelected);

        FrameLayout.LayoutParams layoutParams = (LayoutParams) mSelectorHolder.itemView.getLayoutParams();
        layoutParams.gravity = Gravity.CENTER;
    }

    public void setCategoriesAdapterFromMenu(@MenuRes int menuRes) {
        Menu menu = new MenuBuilder(getContext());
        new MenuInflater(getContext()).inflate(menuRes, menu);
        setCategoriesAdapterFromMenu(menu);
    }

    public void setCategoriesAdapterFromMenu(@NonNull Menu menu) {
        setCategoriesAdapter(new SimpleCategoriesMenuAdapter(menu));
    }

    /**
     * You can setup {@code {@link LoopBarView#mOuterAdapter }} through {@link ViewPager} adapter.
     * Your {@link ViewPager} adapter must implement {@link ILoopBarPagerAdapter} otherwise - the icons will not be shown
     *
     * @param viewPager - viewPager, which must have {@link ILoopBarPagerAdapter}
     */
    public void setupWithViewPager(@NonNull ViewPager viewPager) {
        PagerAdapter pagerAdapter = viewPager.getAdapter();
        List<ICategoryItem> categoryItems = new ArrayList<>(pagerAdapter.getCount());
        ILoopBarPagerAdapter loopBarPagerAdapter =
                pagerAdapter instanceof ILoopBarPagerAdapter
                        ? (ILoopBarPagerAdapter) pagerAdapter : null;
        for (int i = 0, size = pagerAdapter.getCount(); i < size; i++) {
            categoryItems.add(new CategoryItem(
                    loopBarPagerAdapter != null ? loopBarPagerAdapter.getPageDrawable(i) : null,
                    String.valueOf(pagerAdapter.getPageTitle(i))
            ));
        }
        setCategoriesAdapter(new SimpleCategoriesAdapter(categoryItems));
    }

    /**
     * Add item click listener to this view
     *
     * @param itemClickListener Instance of {@link OnItemClickListener}
     * @return always true.
     */
    @SuppressWarnings("unused")
    public boolean addOnItemClickListener(OnItemClickListener itemClickListener) {
        return mClickListeners.add(itemClickListener);
    }

    /**
     * remove item click listener from this view
     *
     * @param itemClickListener Instance of {@link OnItemClickListener}
     * @return true if this {@code List} was modified by this operation, false
     * otherwise.
     */
    @SuppressWarnings("unused")
    public boolean removeOnItemClickListener(OnItemClickListener itemClickListener) {
        return mClickListeners.remove(itemClickListener);
    }

    private void notifyItemClickListeners(int normalizedPosition) {
        for (OnItemClickListener itemClickListener : mClickListeners) {
            itemClickListener.onItemClicked(normalizedPosition);
        }
    }

    /**
     * Return RecyclerView wrapped inside of view for control animations
     * Don't use it for changing adapter inside.
     * Use {@link #setCategoriesAdapter(RecyclerView.Adapter)} instead
     *
     * @return instance of {@link RecyclerView}
     * @deprecated use {@link #setItemAnimator(RecyclerView.ItemAnimator)}, {@link #isAnimating()},
     * {@link #addItemDecoration(RecyclerView.ItemDecoration)},
     * {@link #addItemDecoration(RecyclerView.ItemDecoration, int)},
     * {@link #removeItemDecoration(RecyclerView.ItemDecoration)},
     * {@link #invalidateItemDecorations()},
     * {@link #addOnScrollListener(RecyclerView.OnScrollListener)},
     * {@link #removeOnScrollListener(RecyclerView.OnScrollListener)}
     * {@link #clearOnScrollListeners()} instead
     */
    @Deprecated
    public RecyclerView getWrappedRecyclerView() {
        return rvCategories;
    }

    private RecyclerView getRvCategories() {
        return rvCategories;
    }

    /**
     * Sets the {@link RecyclerView.ItemAnimator} that will handle animations involving changes
     * to the items in wrapped RecyclerView. By default, RecyclerView instantiates and
     * uses an instance of {@link DefaultItemAnimator}. Whether item animations are
     * enabled for the RecyclerView depends on the ItemAnimator and whether
     * the LayoutManager {@link RecyclerView.LayoutManager#supportsPredictiveItemAnimations()
     * supports item animations}.
     *
     * @param animator The ItemAnimator being set. If null, no animations will occur
     *                 when changes occur to the items in this RecyclerView.
     */
    @SuppressWarnings("unused")
    public final void setItemAnimator(RecyclerView.ItemAnimator animator) {
        getRvCategories().setItemAnimator(animator);
    }

    /**
     * Returns true if wrapped RecyclerView is currently running some animations.
     * <p>
     * If you want to be notified when animations are finished, use
     * {@link RecyclerView.ItemAnimator#isRunning(RecyclerView.ItemAnimator.ItemAnimatorFinishedListener)}.
     *
     * @return True if there are some item animations currently running or waiting to be started.
     */
    @SuppressWarnings("unused")
    public final boolean isAnimating() {
        return getRvCategories().isAnimating();
    }

    /**
     * Add an {@link RecyclerView.ItemDecoration} to wrapped RecyclerView. Item decorations can
     * affect both measurement and drawing of individual item views.
     * <p>
     * <p>Item decorations are ordered. Decorations placed earlier in the list will
     * be run/queried/drawn first for their effects on item views. Padding added to views
     * will be nested; a padding added by an earlier decoration will mean further
     * item decorations in the list will be asked to draw/pad within the previous decoration's
     * given area.</p>
     *
     * @param decor Decoration to add
     */
    @SuppressWarnings("unused")
    public final void addItemDecoration(RecyclerView.ItemDecoration decor) {
        getRvCategories().addItemDecoration(decor);
    }

    /**
     * Add an {@link RecyclerView.ItemDecoration} to wrapped RecyclerView. Item decorations can
     * affect both measurement and drawing of individual item views.
     * <p>
     * <p>Item decorations are ordered. Decorations placed earlier in the list will
     * be run/queried/drawn first for their effects on item views. Padding added to views
     * will be nested; a padding added by an earlier decoration will mean further
     * item decorations in the list will be asked to draw/pad within the previous decoration's
     * given area.</p>
     *
     * @param decor Decoration to add
     * @param index Position in the decoration chain to insert this decoration at. If this value
     *              is negative the decoration will be added at the end.
     */
    @SuppressWarnings("unused")
    public final void addItemDecoration(RecyclerView.ItemDecoration decor, int index) {
        getRvCategories().addItemDecoration(decor, index);
    }

    /**
     * Remove an {@link RecyclerView.ItemDecoration} from wrapped RecyclerView.
     * <p>
     * <p>The given decoration will no longer impact the measurement and drawing of
     * item views.</p>
     *
     * @param decor Decoration to remove
     * @see #addItemDecoration(RecyclerView.ItemDecoration)
     */
    @SuppressWarnings("unused")
    public final void removeItemDecoration(RecyclerView.ItemDecoration decor) {
        getRvCategories().removeItemDecoration(decor);
    }

    /**
     * Invalidates all ItemDecorations in wrapped RecyclerView. If RecyclerView has item decorations, calling this method
     * will trigger a {@link #requestLayout()} call.
     */
    @SuppressWarnings("unused")
    public final void invalidateItemDecorations() {
        getRvCategories().invalidateItemDecorations();
    }

    /**
     * Add a listener to wrapped RecyclerView that will be notified of any changes in scroll state or position.
     * <p>
     * <p>Components that add a listener should take care to remove it when finished.
     * Other components that take ownership of a view may call {@link #clearOnScrollListeners()}
     * to remove all attached listeners.</p>
     *
     * @param listener listener to set or null to clear
     */
    @SuppressWarnings("unused")
    public final void addOnScrollListener(RecyclerView.OnScrollListener listener) {
        getRvCategories().addOnScrollListener(listener);
    }

    /**
     * Remove a listener from wrapped RecyclerView that was notified of any changes in scroll state or position.
     *
     * @param listener listener to set or null to clear
     */
    @SuppressWarnings("unused")
    public final void removeOnScrollListener(RecyclerView.OnScrollListener listener) {
        getRvCategories().removeOnScrollListener(listener);
    }

    /**
     * Remove all secondary listener from wrapped RecyclerView that were notified of any changes in scroll state or position.
     */
    @SuppressWarnings("unused")
    public final void clearOnScrollListeners() {
        getRvCategories().clearOnScrollListeners();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        overlayPlaceholder = ((ViewGroup) getParent()).findViewById(mPlaceHolderId);
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        setCurrentItem(ss.mCurrentItemPosition);
        setGravity(ss.mSelectionGravity);
        setIsInfinite(ss.mIsInfinite);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (!mSkipNextOnLayout) {

            if (mOverlaySize > 0 && overlayPlaceholder == null) {
                Log.e(TAG, "You have to add placeholder and set it id with #enls_placeHolderId parameter to use mOverlaySize");
            }

            mOrientationState.initPlaceHolderAndOverlay(overlayPlaceholder, rvCategories, mOverlaySize);

            if (rvCategories.getChildCount() > 0 && !mIndeterminateInitialized) {
                mIndeterminateInitialized = true;
                //scroll to middle of indeterminate recycler view on initialization and if user somehow scrolled to start or end
                rvCategories.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mLinearLayoutManager.scrollToPositionWithOffset(Integer.MAX_VALUE / 2, getResources().getDimensionPixelOffset(R.dimen.enls_selected_view_size_plus_margin));
                        rvCategories.addOnScrollListener(mIndeterminateOnScrollListener);
                        rvCategories.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });

            }

            mSkipNextOnLayout = true;
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return new SavedState(superState,
                mCurrentItemPosition, mSelectionGravity, mInfinite);
    }

    private void startSelectedViewOutAnimation(int position) {
        Animator animator = mSelectionOutAnimator;
        animator.setTarget(mSelectorHolder.itemView);
        animator.start();
        animator.addListener(new AbstractAnimatorListener() {
            @SuppressWarnings("unchecked assigment")
            @Override
            public void onAnimationEnd(Animator animation) {
                //replace selected view
                mSelectorHolder.bindItemWildcardHelper(mInputAdapter, position);
                startSelectedViewInAnimation();
            }
        });
    }

    private void startSelectedViewInAnimation() {
        Animator animator = mSelectionInAnimator;
        animator.setTarget(mSelectorHolder.itemView);
        animator.start();
    }

    /**
     * Set selected item in endless view. OnItemSelected listeners won't be invoked
     *
     * @param currentItemPosition selected position
     */
    public void setCurrentItem(int currentItemPosition) {
        selectItem(currentItemPosition, false);
    }

    /**
     * Set selected item in endless view.
     * OnItemSelected listeners won't be invoked
     *
     * @param currentItemPosition selected position
     * @param isInvokeListeners   should view notify OnItemSelected listeners about this selection
     */
    @SuppressWarnings("unused")
    public void setCurrentItem(int currentItemPosition, boolean isInvokeListeners) {
        selectItem(currentItemPosition, isInvokeListeners);
    }

    public void selectItem(int position, boolean invokeListeners) {
        IOperationItem item = mOuterAdapter.getItem(position);
        IOperationItem oldHidedItem = mOuterAdapter.getItem(mRealHidedPosition);

        int realPosition = mOuterAdapter.normalizePosition(position);
        //do nothing if position not changed
        if (realPosition == mCurrentItemPosition) {
            return;
        }
        int itemToShowAdapterPosition = position - realPosition + mRealHidedPosition;

        item.setVisible(false);

        startSelectedViewOutAnimation(position);

        mOuterAdapter.notifyRealItemChanged(position);
        mRealHidedPosition = realPosition;

        oldHidedItem.setVisible(true);
        flContainerSelected.requestLayout();
        mOuterAdapter.notifyRealItemChanged(itemToShowAdapterPosition);
        this.mCurrentItemPosition = realPosition;

        if (invokeListeners) {
            notifyItemClickListeners(realPosition);
        }

        Log.i(TAG, "clicked on position =" + position);
    }

    @Override
    public void onItemClicked(int position) {
        selectItem(position, true);
    }

    //orientation state factory method
    public IOrientationState getOrientationStateFromParam(int orientation) {
        return orientation == Orientation.ORIENTATION_VERTICAL ? new OrientationStateVertical() : new OrientationStateHorizontal();
    }

    @IntDef({SELECTION_GRAVITY_START, SELECTION_GRAVITY_END})
    public @interface GravityAttr {
    }

    public static class SavedState extends BaseSavedState implements Parcelable {

        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {

            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
        private int mCurrentItemPosition;
        @GravityAttr
        private int mSelectionGravity;
        private boolean mIsInfinite;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @SuppressWarnings("unused")
        private SavedState(Parcel parcel) {
            super(parcel);
            mCurrentItemPosition = parcel.readInt();
            @GravityAttr
            int gravity = parcel.readInt();
            this.mSelectionGravity = gravity;
            boolean[] booleanValues = new boolean[1];
            parcel.readBooleanArray(booleanValues);
            mIsInfinite = booleanValues[0];
        }

        SavedState(Parcelable superState, int currentItemPosition, int selectionGravity, boolean isInfinite) {
            super(superState);
            mCurrentItemPosition = currentItemPosition;
            mSelectionGravity = selectionGravity;
            mIsInfinite = isInfinite;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mCurrentItemPosition);
            parcel.writeInt(mSelectionGravity);
            parcel.writeBooleanArray(new boolean[]{mIsInfinite});
        }
    }

}
