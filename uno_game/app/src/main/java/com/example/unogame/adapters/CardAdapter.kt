package com.example.unogame.adapters

import android.graphics.Color as AndroidColor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.example.unogame.R
import com.example.unogame.models.Card
import com.example.unogame.models.Color as UnoColor // Alias to avoid clash
import com.example.unogame.models.Value

class CardAdapter(
    private var cards: List<Card>,
    private val onCardClicked: (Card) -> Unit
) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val card = cards[position]
        holder.bind(card)
        holder.itemView.setOnClickListener { onCardClicked(card) }
    }

    override fun getItemCount(): Int = cards.size

    fun updateCards(newCards: List<Card>) {
        cards = newCards
        notifyDataSetChanged() // Consider DiffUtil for better performance later
    }

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardBackground: ConstraintLayout = itemView.findViewById(R.id.constraintLayoutCardFace)
        private val valueTop: TextView = itemView.findViewById(R.id.textViewCardValueTop)
        private val valueCenter: TextView = itemView.findViewById(R.id.textViewCardValueCenter)
        private val valueBottom: TextView = itemView.findViewById(R.id.textViewCardValueBottom)

        fun bind(card: Card) {
            val cardText = getCardText(card.value)
            valueTop.text = cardText
            valueCenter.text = cardText
            valueBottom.text = cardText // Already rotated in XML

            cardBackground.setBackgroundColor(getAndroidColorForUno(card.color))

            // Wild cards need special text color if background is black
            if (card.color == UnoColor.WILD) {
                valueTop.setTextColor(AndroidColor.WHITE)
                valueCenter.setTextColor(AndroidColor.WHITE)
                valueBottom.setTextColor(AndroidColor.WHITE)
            } else {
                // Reset to default or a color that contrasts with the card color
                valueTop.setTextColor(AndroidColor.WHITE) // Assuming white text for colored cards
                valueCenter.setTextColor(AndroidColor.WHITE)
                valueBottom.setTextColor(AndroidColor.WHITE)
            }
        }

        private fun getCardText(value: Value): String {
            return when (value) {
                Value.ZERO -> "0"
                Value.ONE -> "1"
                Value.TWO -> "2"
                Value.THREE -> "3"
                Value.FOUR -> "4"
                Value.FIVE -> "5"
                Value.SIX -> "6"
                Value.SEVEN -> "7"
                Value.EIGHT -> "8"
                Value.NINE -> "9"
                Value.SKIP -> "S" // Skip
                Value.REVERSE -> "R" // Reverse
                Value.DRAW_TWO -> "+2"
                Value.WILD -> "W" // Wild
                Value.WILD_DRAW_FOUR -> "+4W"
            }
        }

        private fun getAndroidColorForUno(unoColor: UnoColor): Int {
            return when (unoColor) {
                UnoColor.RED -> AndroidColor.parseColor("#FFD32F2F") // Red 700
                UnoColor.YELLOW -> AndroidColor.parseColor("#FFFBC02D") // Yellow 700
                UnoColor.GREEN -> AndroidColor.parseColor("#FF388E3C") // Green 700
                UnoColor.BLUE -> AndroidColor.parseColor("#FF1976D2") // Blue 700
                UnoColor.WILD -> AndroidColor.parseColor("#FF000000") // Black for Wild
            }
        }
    }
}
